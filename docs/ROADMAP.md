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
| `GET k` | parallel read from R replicas | return **max(version)**; async read-repair laggers |
| `GET /kv` | fan-out to all live nodes, dedupe by key | best-effort union |

**Why primary-coordinated CAS instead of per-key Raft?** Full linearizability via Raft per key is operationally heavy (N Raft groups per key). The CAS contract clients already see — "I expected version V, you tell me if I'm wrong" — is satisfiable by having the primary serialise version checks, then quorum-replicate the result. Cassandra and Riak both land here; we'd be in good company.

---

## Failure modes

| Failure | Behavior |
|---|---|
| Single replica down | Writes still succeed if W remaining ack. Reads use any R live replicas. |
| Primary down | Next replica on ring takes over as coordinator (ring is deterministic; no election). |
| Replica returns stale read | Coordinator sees lower `version`, picks max, **read-repair** writes the latest back. |
| Network partition (CP choice) | Minority side refuses writes (`503`). Majority side keeps serving. On heal, anti-entropy reconciles. |
| Replica down for a while, then back | **Hinted handoff**: coordinator buffered writes for it; replays on rejoin. **Anti-entropy** (Merkle-tree compare) catches anything the handoffs missed. |
| New node added | Ring updates; key ranges from neighbouring nodes stream over. Steady-state traffic continues during transfer (reads can still hit old owners; writes go to new owners with hinted handoff). |

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
5. Add R quorum reads          max(version) wins; async read-repair
6. Hinted handoff              buffer writes for down replicas; replay on rejoin
7. Anti-entropy / Merkle sync  background drift correction
8. Swap static membership → gossip
```

Effort sketch:

- **Steps 1–5: ~2 weeks** — the system is recognisably fault-tolerant by step 5.
- **Steps 6–8: ~3–4 weeks** — what separates "tolerant of clean failures" from "tolerant of nasty failures".

---

## What's deliberately *not* on this roadmap

- **Per-key Raft / Paxos.** For our `ifVersion`-based CAS, primary-coordinated quorum is sufficient. Linearizability isn't required, and N Raft groups per key is operationally huge.
- **Cross-region replication.** Different problem (latency-dominated), different tools (CRDTs, async multi-master, eventual consistency).
- **Range queries / secondary indexes.** Would force range partitioning instead of hashing.
- **Persistence.** Important, but its own line item. WAL+snapshots can ship before, after, or in parallel with this work.

---

## Discussion hooks

1. **"What if `W = N = 1`?"** That's where we are today; degenerate case of the same design.
2. **"What if `W = 1, R = N`?"** Fast writes, slow reads, still strongly consistent. Useful for write-heavy / read-cold.
3. **"Stale node rejoins."** Refuse reads from it until Merkle convergence; or weight it last in the read set until repaired.
4. **"PATCH shallow-merge under replication."** Merge happens at the primary before fan-out. Replicas store the post-merge value. Avoids commutativity questions.
5. **"Why not just use Cassandra/Riak?"** A reasonable alternative answer. The roadmap is "what would I build if I had to" — the off-the-shelf answer is "use one of those".
