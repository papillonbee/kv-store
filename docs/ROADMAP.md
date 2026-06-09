# Roadmap — fault-tolerant, elastically scalable kv-store

> Part 3 deliverable. Design only — no code changes implied.

## TL;DR

Replace the static-modulo router with **consistent hashing + N-way replication + quorum reads/writes**. This makes the cluster *fault-tolerant* (today any node loss = data loss for that node's keys) and *elastically scalable* (today adding a node remaps ~75% of keys).

---

## What's broken today

```
                Single point of failure per key
                              ↓
        Router  ───→  node-2  (crashes)
                         ╳  ╳  ╳
                  every key it owned is gone
```

Two structural gaps that Part 2 doesn't address:

1. **No redundancy.** A node loss takes its entire key set offline. With in-memory storage, the data is gone permanently.
2. **Catastrophic rebalance.** `Math.floorMod(key.hashCode(), N)` means scaling from 3 → 4 nodes remaps roughly **3/4 of all keys**. The cluster can't grow without an offline migration.

---

## End state

```
                          ┌─ Router (any of N) ─┐
            Client  ───→  │  consistent hash    │
                          │   ring lookup       │
                          └──────────┬──────────┘
                                     │
                       ┌─────────────┼─────────────┐
                       ▼             ▼             ▼
                    owner       replica-1     replica-2
                   (token A)   (next on ring)
                    node-1       node-2        node-3

                    Replication factor    N = 3
                    Write quorum          W = 2
                    Read quorum           R = 2
                                W + R > N  ⇒  strong consistency
```

Concretely:

- **Hash ring with vnodes.** Each physical node owns hundreds of virtual tokens on a 2⁶⁴ ring. Adding a node steals roughly `1/(N+1)` of keys, evenly distributed.
- **N replicas per key.** First ring owner is "primary" (purely a tiebreak label for CAS coordination); the next `N-1` neighbours hold replicas.
- **Quorum reads/writes** with `W + R > N` give linearizable behavior for the existing CAS semantics.

---

## Read/write paths

| Operation | Coordinator's job | Success criterion |
|---|---|---|
| `PUT k=v` (no ifVersion) | parallel write to all N replicas | ≥ W replicas ack → 200 |
| `PUT k=v ifVersion=V` | primary checks version, fans out to replicas | ≥ W ack → 200; primary mismatch → 409 |
| `GET k` | parallel read from R replicas; refuse with `503` if < R live | return **max(version)** across responses |
| `GET /kv` | fan-out to all live nodes, dedupe by key | best-effort union |

## Architecture pick: primary-coordinated CAS

The `ifVersion=N` check needs *some* node to serialise concurrent attempts so exactly one of two racers succeeds. We pick the lightest mechanism that meets that contract.

```
                       Client
                         │
            ┌────────────┴────────────┐
            ▼                         ▼
     any router node            any router node
            │                         │
            │     (hash → same primary)
            └─────────┬───────────────┘
                      ▼
              ╔═════════════╗
              ║   primary   ║   first replica on the ring for this key.
              ║  (node X)   ║   deterministic; no election; every router
              ╚══════╤══════╝   computes the same answer from the ring.
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
    replica-a    replica-b    replica-c     (followers — apply what primary tells them)
    (next on ring)
```

The primary is the **single‑threaded version‑check point** for its key. Followers just receive writes and apply them; they don't vote on anything.

```
PUT k ifVersion=V at the primary:

   1. read entry.version (local)
   2. if (V != entry.version) → 409
   3. write {newValue, V+1} locally
   4. send the same write to N-1 replicas in parallel
   5. wait until W-1 acks come back
   6. respond 200 to the client
```

### What this gives us

Clients buy exactly two properties from `ifVersion`:

1. **Per-key serialisation** — concurrent `ifVersion=N` writes resolve to one winner and one 409.
2. **Visibility** — a version returned from a read was actually committed by some writer.

A single primary supplies both, cheaply:

| Property | How the primary satisfies it |
|---|---|
| Per-key serialisation | All writes for the key route to one node; the version check is a single-threaded code path there. |
| Visibility under `R+W>N` | Primary doesn't ack until W replicas confirm; reads from R replicas always intersect at least one writer's quorum. |
| Read-after-write for the writer | Trivially: writer's response already includes the new version. |

### What we explicitly don't sell

- **Cross-key ordering.** Two writes to *different* keys can be observed in either order. Clients never asked for this.
- **Clean status on partial-replication writes.** When a write reaches *some* replicas but not enough for the primary to declare success, the cluster is temporarily inconsistent on that key and the client gets a non-2xx that doesn't tell them what actually happened. See "Grey-zone writes" below.

### Failover when the primary dies

```
    ring:  node-1 ── node-2 ── node-3 ── node-4 ── (back to node-1)
             ▲
             └─ primary for "user:42"

    node-1 dies.

    Every router recomputes: "first live ring neighbour for user:42 is now node-2."
    node-2 becomes the new primary. No election, no agreement protocol — the ring
    is deterministic and gossip-propagated cluster membership tells everyone who's live.
```

Handoff is just routing. What happens to a write that the *old* primary had accepted but not finished replicating is the same problem as a write that timed out without reaching W acks — handled together in the next subsection.

### Grey-zone writes: when the client doesn't get a clean 200 or 409

Two situations leave a single key in a temporarily inconsistent state, with the client unsure whether their write took effect:

1. **`<W` acks before timeout.** Primary committed locally and replicated to some followers, but not enough acked in time. Returns `503`.
2. **Primary crashed mid-write.** Primary accepted the write, possibly replicated to some followers, then died. Client gets a timeout.

Both end with the same cluster state — write committed on a *subset* of replicas:

```
   key "user:42"  before:    version=5   on every replica

   client → primary
       1. primary writes locally → version=6 on primary
       2. forwards to replica-A, replica-B
       3. replica-A acks; replica-B silent; primary times out (or dies)
       4. client receives 503 (or a connection timeout)

   key "user:42"  after:
       primary    → version=6, newValue   (alive: still has it; dead: on disk if it returns)
       replica-A  → version=6, newValue   (acked the write)
       replica-B  → version=5, oldValue   (never received it OR received but didn't ack)
                    ──────────  three indistinguishable cases:
                                  • crashed before receiving
                                  • received, wrote, crashed before acking → has newValue
                                  • slow; ack eventually arrives           → has newValue
```

We do **not** roll back the partial write. Doing so cleanly would require its own quorum protocol (when does the primary tell replica-A "actually never mind"? what if *that* message fails?) — that's the second half of a consensus algorithm, which is exactly what we said we wouldn't run.

How the inconsistency heals:

- **Next quorum read** of the key combines R replicas and picks `max(version)`. If any of the R replicas read carries the new write, the new value is returned and the write "won". If none of the R replicas read have it, the old value is returned and the write "lost". Which way it goes depends on which subset of replicas the coordinator happens to read.
- **Next successful write** to the key replicates normally via quorum and overwrites the row everywhere; consistency restored.
- **State transfer on rejoin** brings any catching-up replica to whatever the live quorum sees.

The honest cost the client pays:

- *"I got 503, so my write didn't take effect"* — **wrong**. It may have. The next read might surface it.
- After a 503 or timeout, the client **must `GET` the key** to learn the truth.
- For `ifVersion`-based clients this rarely matters in practice: the next conditional retry surfaces reality. Either the new version is there (write succeeded; retry → 409) or it isn't (write lost; retry → 200).

This is the price for not running a consensus protocol. Cassandra LWT, etcd, Spanner all resolve this cleanly via the protocols we chose not to run.

### Where comparable systems land

| System | Coordination model |
|---|---|
| Cassandra LWT | Per-write agreement round, driven by the request's coordinator node |
| Riak (strong-consistency mode, deprecated) | Per-bucket agreement; one leader per bucket of keys |
| DynamoDB conditional writes | Internal primary per partition, leader-driven — the closest analog to our pick |
| **Our pick** | **Primary-coordinated CAS, primary chosen by ring position** |

Common thread: every one of these coordinates at a *coarser* granularity than per-key (bucket, partition, or per-request). Per-client contracts don't justify the cost of per-key coordination machinery.

---

## Failure modes

The rule for every row below is the same: **accept the operation only if we can durably make the guarantee right now**. If we can't, refuse with `503` and let the client retry. No background buffering of "accepted but undelivered" writes — that's the AP playbook (Dynamo/Cassandra hinted handoff), and it's incompatible with the CP contract we sold to clients in the consistency-model decision.

| Failure | Behavior |
|---|---|
| Single replica down (W still reachable) | Writes succeed once W replicas ack. Reads succeed from any R live replicas. |
| Cannot reach W live replicas | Write returns `503`. **Replicas that already accepted the write keep it** — we do not roll back; the key is in a grey zone until the next quorum read or successful write. See *"Grey-zone writes"* above. |
| Cannot reach R live replicas | Read returns `503`. |
| Primary down | Next live ring neighbour becomes primary (deterministic, no election). In-flight writes time out and must be retried; if they were partially replicated before the crash, they fall into the same grey zone (above). |
| Network partition | Minority side refuses both reads and writes with `503`. Majority side keeps serving. |
| Replica rejoins after downtime | Replica catches up via **state transfer** before counting toward W or R. While catching up, it accepts no client traffic. |
| New node added | New node streams its ring-range from neighbours; joins the read/write quorum set only after catch-up completes. |
| Replica returns stale value despite the above | Quorum read combines responses; max(version) wins. Should be rare in steady state — included as belt-and-suspenders. |

The key thing missing from this list, deliberately: **hinted handoff** and **async read-repair as a primary correctness mechanism**. Those exist in AP systems to *avoid* the `503`s above. We accept the `503`s — that's what choosing CP means.

### State transfer: how a catching-up replica reaches "current"

Two rows above use the phrase "catches up via state transfer" without saying what that is. It's not a protocol — it's just **copy the keys this replica should own from a live peer until our local store matches the cluster's current quorum view**. Same primitive on both code paths (rejoin after downtime, and new-node join).

Mechanically, for the range of keys this replica is responsible for:

```
1. From current cluster membership (gossip), compute the ring range I should own.
2. Pick a healthy peer that already owns the same range — any of the N-1 other replicas.
3. Ask the peer: "for every key in my range, what's the current version?"
4. For every (key, version) where my local copy is missing or stale:
       fetch the value from the peer, write it locally.
5. Once the diff is empty, announce "I'm in" via gossip; start counting toward W and R.
```

The peer is chosen by ring position; nothing about state transfer requires consensus or a quorum vote.

**Naive vs. production-efficient.** A naive implementation lists every key and version-compares one by one — O(K) round trips for K keys. The standard optimisation is **Merkle trees**: each replica maintains a hash tree over its key range, comparing roots is O(1), and divergent subtrees are localised in O(log K). State transfer then streams only the differing keys. Cassandra, Riak, DynamoDB all do this. For an assignment-grade build, list-and-compare is fine; Merkle is an optimisation, not a correctness requirement.

**What happens to concurrent writes during transfer.** A key being transferred might be written to by a normal quorum write at the same time. Two viable approaches:

| Strategy | How it works | When it's right |
|---|---|---|
| Double-write during transfer | The live quorum forwards every new write to the catching-up replica too. It applies them even before joining. When bulk transfer finishes, no separate "catch up to writes during transfer" pass is needed. | Simple; works well when writes are infrequent. |
| Iterate until convergence | Take an approximate snapshot, copy it, then do a second pass for writes that happened during the first pass. Repeat until the delta is empty. | More robust under heavy concurrent write load. |

**What state transfer does NOT do.**
- Doesn't take any locks on the live cluster.
- Doesn't pause writes — the cluster keeps serving from the live N-1 replicas the whole time.
- Doesn't participate in any consensus protocol. It's a plain copy + `max(version)` reconciliation.
- Doesn't touch keys this replica doesn't own. No role in cross-key ordering.
- Doesn't serve client traffic while running — the catching-up replica is invisible to W/R quorum counting until step 5 completes.

---

## Tradeoffs

| Decision | Pick | Why | What I'd give up |
|---|---|---|---|
| Consistency model | **CP** (`W+R>N`, refuse on partition) | Matches our CAS-driven client contract; clients already expect `409`. | Availability during partitions — some writes return `503`. |
| Membership protocol | **Gossip** (SWIM-style) | No SPOF, no external Zookeeper. | Eventually-consistent view; harder to reason about cluster snapshots. |
| Replica selection | **Vnode ring** (256 tokens/node) | Smooth load distribution; cheap rebalance. | More metadata to gossip about. |
| Replication topology | **Single-region** | Lower hops, simpler ops. | No DR; orthogonal concern. |
| Persistence | **Punted** | Independent concern; can ship WAL+snapshots per node in isolation. | Restart still empties a node until persistence lands. |

---

## Migration sequence

Each step is independently shippable and reversible.

```
1. Ring & vnodes               replace KeyRouter; keep N=1 (no replication yet)
2. Static membership config    each process knows the full node list at boot
3. N>1 writes, best-effort     fan out, but W=1 — exercises the multi-write path
4. Add W quorum                refuse on <W successes (503 + retry-after)
5. Add R quorum reads          max(version) wins
6. Catch-up on rejoin          downed replica streams state from neighbours before
                               re-entering the quorum set
7. Catch-up on new node added  new node syncs ring-range before serving traffic
8. Swap static membership → gossip
```

Effort sketch:

- **Steps 1–5: ~2 weeks** — the system is recognisably fault-tolerant by step 5.
- **Steps 6–8: ~3–4 weeks** — operational polish for live cluster changes.

---

## What's deliberately *not* on this roadmap

- **Per-key consensus protocols.** Stronger machinery (durable per-key replicated logs, leader election, etc.) would give us full linearizability across every operation, including writes to different keys. We don't need that — `ifVersion` is a per-key contract — so we don't pay for it.
- **Cross-region replication.** Different problem (latency-dominated), different tools (CRDTs, async multi-master, eventual consistency).
- **Range queries / secondary indexes.** Would force range partitioning instead of hashing.
- **Persistence.** Important, but its own line item. WAL+snapshots can ship before, after, or in parallel with this work.

---

## Discussion hooks

1. **"What if `W = N = 1`?"** That's where we are today; degenerate case of the same design.
2. **"What if `W = 1, R = N`?"** Fast writes, slow reads, still strongly consistent. Useful for write-heavy / read-cold.
3. **"Stale node rejoins."** It does **not** count toward W or R until catch-up completes — only then does it serve traffic. No "serve stale reads while repairing in the background" — that's the AP smoothing we explicitly chose not to do.
4. **"PATCH shallow-merge under replication."** Merge happens at the primary before fan-out. Replicas store the post-merge value. Avoids commutativity questions.
5. **"Why not just use Cassandra/Riak?"** A reasonable alternative answer. The roadmap is "what would I build if I had to" — the off-the-shelf answer is "use one of those".
