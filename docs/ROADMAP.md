# Roadmap — fault-tolerant, elastically scalable kv-store

> Part 3 deliverable. Design only — no code changes implied.

## Proposal

Replace the static `hash(key) % N` router with **consistent hashing, N-way replication, and quorum reads/writes**. This is the natural next step after Part 2: the store already has per-key versioning and `ifVersion` CAS, but today any node loss loses that node's keys and adding a node remaps most of them.

---

## Diagram

```
                    Client
                      │
                      ▼
              ┌───────────────┐
              │    Router     │  consistent-hash ring lookup
              └───────┬───────┘
                      │
        ┌─────────────┼─────────────┐
        ▼             ▼             ▼
     primary      replica-1     replica-2
     (node-1)      (node-2)      (node-3)

     Replication factor  N = 3
     Write quorum        W = 2
     Read quorum         R = 2
     W + R > N           ⇒ strong per-key consistency
```

**Write path (simplified):** router finds the key's primary (first live owner on the ring) → primary applies the write (including `ifVersion` check) → fans out to replicas → succeeds when ≥ W replicas ack.

**Read path:** router reads from R replicas in parallel → returns the entry with the highest version.

---

## The change

| Today (Part 2) | Proposed |
|---|---|
| One copy per key on a single node | Three copies spread across ring neighbours |
| `Math.floorMod(hash, N)` routing | Hash ring with virtual nodes for even load |
| Static node list at boot | Static list first, gossip membership later |
| Node down → 502 + data loss for its keys | Node down → quorum still serves; data survives |

The existing `KvStore` CAS semantics stay the same at the API layer. Replication logic lives in the router/coordinator layer; replicas store the post-write value the primary sends them.

---

## Expected benefits

- **Fault tolerance.** Losing one node no longer drops keys that still have quorum on other replicas.
- **Elastic scaling.** Adding a node moves roughly `1/(N+1)` of keys instead of `N/(N+1)`.
- **Preserves the client contract.** `ifVersion` still means one winner and one `409` per key; quorum (`W + R > N`) keeps reads and writes consistent enough for that model.

---

## Tradeoffs

| Choice | Why | Cost |
|---|---|---|
| **CP over AP** (`W + R > N`, refuse on partition) | Matches existing CAS semantics | Some reads/writes return `503` during outages or partitions |
| **Primary-coordinated CAS** (ring picks primary, no election) | Cheapest way to serialise `ifVersion` per key | After a `503` or timeout, clients should `GET` to learn whether a write landed |
| **Gossip membership** (later step) | No external coordinator | Cluster view is eventually consistent |
| **No per-key consensus** (Paxos/Raft) | `ifVersion` is per-key; full linearizability across keys isn't required | Grey-zone writes possible when `< W` replicas ack |

Persistence (WAL + snapshots) is orthogonal and can ship in parallel.

---

## Rough sequence & effort

Each step is independently shippable.

```
1. Consistent-hash ring     replace KeyRouter; still N=1 (no replication)
2. Static membership        full node list known at boot
3. Multi-replica writes     fan-out with W=1 (exercise the path)
4. Write quorum (W=2)        refuse when < W acks
5. Read quorum (R=2)         max(version) across R replicas
6. Replica catch-up          rejoining or new nodes sync before serving
7. Gossip membership         replace static config
```

**Effort sketch:** steps 1–5 ≈ 2 weeks for a recognisably fault-tolerant cluster; steps 6–7 ≈ 3–4 more weeks for live join/leave.

<details>
<summary>Implementation notes</summary>


Each step builds on the previous one. The guiding rule: **keep `KvStore` CAS on the primary**; replicas are dumb followers that apply `{value, version}` the primary sends. Replication and quorum logic live in the router/coordinator layer.

| Component | Today | After steps 1–5 |
|---|---|---|
| `KeyRouter` | `floorMod(hash, N)` | Ring + replica-set lookup |
| `RouterController` | Proxy to one node | Coordinate quorum reads/writes |
| `KvController` | Client CRUD | + internal replicate/sync endpoints |
| `KvStore` | Per-key CAS | + `applyReplica()` for follower writes |
| `GlobalExceptionHandler` | 400/409/502 | + 503 for quorum failures |

#### 1. Consistent-hash ring (still one copy per key)

**What it does:** Replaces `KeyRouter.nodeFor()`'s modulo hash with a ring lookup. Each physical node gets many virtual tokens (e.g. 256). A key hashes to a point on the ring; the first token clockwise is the owner. Client behaviour is unchanged — still one node per key — but adding a node moves ~`1/(N+1)` of keys instead of most of them.

**How to implement:**

- Add `ConsistentHashRing` in `router/`: sorted `token → nodeId` map; insert `hash(nodeId + "#" + i)` per node; `nodeFor(key)` via `ceilingEntry(hash(key))`.
- Wire in `KvStoreApplication.keyRouter()` — needs node IDs alongside URLs (today only URLs).
- `RouterController` unchanged.
- Update `KeyRouterTest`: stability, distribution, "add 4th node → ~25% of keys move".

#### 2. Static membership

**What it does:** Every process shares the same cluster view at boot: who exists, who is alive, how the ring is built. Part 2 already has `--kvstore.nodes`, but URL-only with no health tracking.

**How to implement:**

- `ClusterMembership` / `NodeRegistry`: `record NodeInfo(String id, String url)` + `liveNodes()` subset.
- Config: structured node list with IDs (not comma-separated URLs alone).
- Lightweight health probe on nodes (`GET /health` or reuse `GET /kv`); router refreshes live set periodically.
- Ring lookup skips dead nodes — walk clockwise until a live one is found.

**Deliverable:** Router won't route to a dead node (today → `502` via `NodeUnreachableException`).

#### 3. Multi-replica writes (W=1)

**What it does:** Every key is written to 3 nodes (primary + 2 ring neighbours). With `W=1`, success = primary acked; replicas are best-effort. Exercises replication plumbing without quorum failure handling.

**How to implement:**

- Extend ring: `replicasFor(key, replicationFactor)` walks clockwise collecting N distinct live nodes; `primaryFor(key)` = first.
- Internal node endpoint (not client-facing):
  - `PUT /internal/replicate/{key}` with `{value, version}` — bypasses `ifVersion`; primary already did the CAS check.
  - New `KvStore.applyReplica(key, value, version)` — write only if `incoming.version > local.version`.
- `RouterController` write path: send normal `PUT`/`PATCH` to primary; on 200, fan out response to replicas via `/internal/replicate/{key}` in parallel.
- Reads still hit one node (primary).

#### 4. Write quorum (W=2)

**What it does:** Write succeeds only when ≥ 2 of 3 replicas confirm. `< W` acks → `503`. Cluster becomes fault-tolerant for writes.

**How to implement:**

- Config: `kvstore.replication.factor=3`, `kvstore.quorum.write=2`.
- Extract `WriteCoordinator` from `RouterController`:
  1. `replicas = ring.replicasFor(key, N=3)`
  2. CAS write to primary (with `ifVersion`)
  3. `409` from primary → pass through
  4. `200` from primary → fan out replicate to other N−1 nodes
  5. Count acks (primary = 1); `≥ W` → 200, else → 503
- Add `ServiceUnavailableException` → 503 in `GlobalExceptionHandler`.
- Replicas that already accepted the write keep it even on 503 (grey zone) — clients should `GET` after ambiguous failures.

**Deliverable:** Cluster survives loss of 1 node for writes.

#### 5. Read quorum (R=2), max(version)

**What it does:** Reads contact R=2 replicas in parallel; return the entry with the highest version. Reads survive 1-node loss and can heal grey-zone inconsistency from step 4.

**How to implement:**

- Config: `kvstore.quorum.read=2`.
- `RouterController` GET path:
  1. `replicas = ring.replicasFor(key, N=3)`; pick R live ones
  2. Parallel `GET /kv/{key}` (e.g. `CompletableFuture` or virtual threads)
  3. `< R` responses → 503; all 404 → 404; else return `max(version)`
- `GET /kv` listing: fan out to all live nodes, dedupe by key keeping max version.
- Test: write with one replica down → read still correct.

**Deliverable:** Steps 1–5 = recognisably fault-tolerant cluster (~2 week milestone).

#### 6. Replica catch-up

**What it does:** A rejoining or newly added node does not count toward W/R until it has copied keys in its ring range. Otherwise it serves stale data and breaks quorum.

**How to implement:**

- Node states in `ClusterMembership`: `LIVE | CATCHING_UP | DEAD` — only `LIVE` counts toward quorum.
- Internal sync API:
  - `GET /internal/keys?from=&to=` → `{key, version}` list for a ring range
  - `GET /internal/entry/{key}` → full entry
  - `POST /internal/apply-batch` → bulk replica apply
- Catch-up on boot/rejoin: compute ring ranges → pick healthy peer → diff keys by version → copy missing/stale → mark `LIVE`.
- Double-write live traffic to catching-up node during bulk transfer so it doesn't fall behind.

#### 7. Gossip membership

**What it does:** Replaces static `--kvstore.nodes` with runtime discovery. Nodes join/leave without restarting the router or editing config.

**How to implement:**

- SWIM-style gossip on every node (not just router): periodic ping to random peer; merge membership tables; mark dead after missed heartbeats.
- On membership change: rebuild ring → trigger catch-up (step 6) for new/changed ranges.
- Bootstrap-only config: `kvstore.gossip.seeds=[...]` instead of full static node list.
- Router's `ClusterMembership` subscribes to gossip events.

**Deliverable:** Elastic join/leave without manual config (~3–4 week polish milestone).
</details>