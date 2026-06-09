# kv-store

An HTTP key-value store with optimistic concurrency control, built as a take-home assignment. One JAR runs in either of two modes — a **storage node** or a **router** — selected at boot by Spring profile.

- **Part 1**: Single-node in-memory store with per-key serialisation, versioning, conditional update (`ifVersion`), and a shallow-merge PATCH.
- **Part 2**: Horizontal scale-out via a router that hashes keys to nodes and fans `GET /kv` out across the cluster.
- **Part 3**: Design-only roadmap for fault-tolerance and elastic scaling — see [docs/ROADMAP.md](docs/ROADMAP.md).

---

## Architecture

### Single node (Part 1)

```
                 ┌──────────────────────────────────────────┐
   HTTP  ──→     │  KvController   (REST surface, /kv/*)    │
                 │       │                                  │
                 │       ▼                                  │
                 │  KvService     (per-key atomicity,       │
                 │       │        versioning, CAS)          │
                 │       ▼                                  │
                 │  ConcurrentMap<String, KvEntry>          │
                 └──────────────────────────────────────────┘
```

### Multi-node (Part 2)

```
                            ┌───────────────────┐
            Client  ───→    │  RouterController │  (router profile)
                            │  + KeyRouter      │
                            └────────┬──────────┘
                                     │  hash(key) % N
                          ┌──────────┼──────────┐
                          ▼          ▼          ▼
                        node-1    node-2    node-3   (node profile, each its own JVM)
                       :7001     :7002     :7003
```

- For `/kv/{key}` the router proxies to the one node that owns the key.
- For `GET /kv` the router fans out to **all** nodes and concatenates their NDJSON listings.

---

## Quick start

### Prerequisites

- JDK 21 (uses Spring Boot 3.3, requires Java 17+)
- Maven 3.9+
- macOS / Linux for the shell scripts (Windows: run the `java -jar` commands directly)

The bundled scripts pin `JAVA_HOME` to JDK 21 inside a subshell, so your default JDK is left alone.

### Cluster demo

```bash
./run-cluster.sh
```

Brings up 3 nodes + 1 router on ports `7001-7003` + `7000`, with per-process logs in `target/logs/`. Ctrl-C tears down everything.

In another terminal:

```bash
# Required test from the assignment: 3 clients incrementing a counter.
curl -X PUT  http://localhost:7000/kv/counter -H 'Content-Type: application/json' -d '{"counter":0}'
curl         http://localhost:7000/kv/counter
curl -X PATCH http://localhost:7000/kv/counter -H 'Content-Type: application/json' -d '{"counter":1}'
curl         http://localhost:7000/kv

# Conditional update (succeeds → 200, mismatch → 409)
curl -X PUT 'http://localhost:7000/kv/counter?ifVersion=2' \
     -H 'Content-Type: application/json' -d '{"counter":3}'
```

### Individual processes

```bash
./run-node.sh   7001 node-1
./run-node.sh   7002 node-2
./run-node.sh   7003 node-3
./run-router.sh 7000 http://localhost:7001,http://localhost:7002,http://localhost:7003
```

### From scratch

```bash
mvn package                       # builds target/kv-store-1.0-SNAPSHOT.jar
java -jar target/kv-store-1.0-SNAPSHOT.jar \
     --spring.profiles.active=node \
     --server.port=7001 \
     --kvstore.node-id=node-1\
     --kvstore.persistence.dir=./data/kv-store

java -jar target/kv-store-1.0-SNAPSHOT.jar \
     --spring.profiles.active=router \
     --server.port=7000 \
     --kvstore.nodes=http://localhost:7001,http://localhost:7002,http://localhost:7003
```

---

## API

All responses are JSON; errors look like `{"error": "<message>"}`.

| Method | Path                       | Body / params                          | Success      | Failure modes |
|--------|----------------------------|----------------------------------------|--------------|---------------|
| GET    | `/kv/{key}`                | —                                      | `200 { key, value, version }` | `404` if missing |
| PUT    | `/kv/{key}`                | JSON value; optional `?ifVersion=N`    | `200` same shape | `400` invalid JSON, `409` version mismatch |
| PATCH  | `/kv/{key}`                | JSON delta; optional `?ifVersion=N`    | `200` same shape | `400`, `409`. If key missing, creates with delta. Shallow-merges when both sides are JSON objects; otherwise replaces. |
| GET    | `/kv`                      | —                                      | `200` NDJSON `{ "key": "…", "node": "…" }` per line, `Content-Type: application/x-ndjson` | — |

### Status code catalog

| Code | Meaning                                                    | Where it's thrown |
|------|------------------------------------------------------------|-------------------|
| 200  | Success                                                    | controller        |
| 400  | Malformed JSON body; bad `ifVersion`; empty key            | `BadRequestException` |
| 404  | Key not found                                              | controller returns null → 404 |
| 405  | Method not allowed (e.g. `DELETE /kv/k`)                   | Spring MVC + `GlobalExceptionHandler` |
| 409  | `ifVersion` doesn't match current version                  | `VersionConflictException` |
| 500  | Anything genuinely unexpected                              | catch-all handler |
| 502  | Router → node call failed                                  | `NodeUnreachableException` (router only) |

The mapping is one-line in [`GlobalExceptionHandler`](src/main/java/com/kvstore/rest/GlobalExceptionHandler.java): `KvStoreException → e.httpStatus()`.

---

## Concurrency design (the interesting part)

Concurrency safety lives entirely in [`KvService.save(...)`](src/main/java/com/kvstore/service/KvService.java):

```java
private KvEntry save(String key, String value, Long ifVersion, boolean patch) {
    JsonNode incoming = JacksonUtil.parse(value);                // outside lock — fail fast
    AtomicReference<KvEntry> snapshot = new AtomicReference<>();
    store.compute(key, (k, existing) -> {                        // bucket lock (ConcurrentHashMap)
        KvEntry stored = existing == null
            ? createEntry(ifVersion, incoming)
            : updateEntry(existing, ifVersion, patch, incoming); // synchronized(entry.getLock())
        snapshot.set(new KvEntry(stored.value.deepCopy(), stored.version));
        return stored;
    });
    return snapshot.get();
}
```

Five properties this gives us, each verified by a test:

1. **Per-key atomicity.** `compute()` serialises every write to the same key (and only the same key — different keys proceed in parallel).
2. **No torn reads/writes.** `get()` reads `value` and `version` together under `entry.getLock()`, the same lock `updateEntry` holds while it mutates — so callers never see value-from-version-N paired with version N+1.
3. **No client mutation of in-store state.** `get()` returns a `deepCopy()` of the JsonNode. The required 3-client counter test would otherwise overshoot the version because clients mutate the cached JSON in place.
4. **Atomic snapshot-after-write.** The post-write response describes the state *this* write produced, captured inside the same `compute()` lambda — so concurrent writers can't make the response describe their value instead of ours.
5. **Optimistic CAS via `ifVersion`.** The version-match check + value swap + version increment happen under `entry.getLock()` and can't be interleaved with another writer's check.

The shape of the lock hierarchy:

```
ConcurrentHashMap bucket lock        (held during the entire compute() lambda)
       │
       └─ entry.getLock()            (per-key; held by get() and by updateEntry)
              │
              └─ entry.value / version mutation
```

JSON parsing happens **before** entering `compute()` so a bad PUT body returns 400 without taking the lock.

---

## Testing

```bash
mvn test       # 36 tests; ~4 s wall clock on a warm JVM (varies by machine)
```

| Suite                                                                                              | Tests | Approx. time | What it covers |
|----------------------------------------------------------------------------------------------------|-------|--------------|----------------|
| [`KvServiceTest`](src/test/java/com/kvstore/service/KvServiceTest.java)                            | 13    | ~20 ms       | Pure-logic: CAS, shallow-merge, concurrent counter, race conditions |
| [`KeyRouterTest`](src/test/java/com/kvstore/router/KeyRouterTest.java)                             | 6     | <10 ms       | Hash routing: stability, distribution, `Integer.MIN_VALUE` hashcode |
| [`KvControllerIntegrationTest`](src/test/java/com/kvstore/rest/KvControllerIntegrationTest.java)   | 10    | ~500 ms      | Single-node HTTP: every status code (200/400/404/405/409). One Spring context, reused across all 10 tests. |
| [`RouterIntegrationTest`](src/test/java/com/kvstore/router/RouterIntegrationTest.java)             | 7     | ~3 s         | Multi-node end-to-end: 3 node contexts + 1 router context spun up **per test method** on ephemeral ports. Includes a "node down → 502" path. |

The router suite dominates because every test starts four fresh Spring contexts in its `@BeforeEach`. A cold JVM (first run after `mvn clean`) can be several times slower.

The required *"3 concurrent clients incrementing a counter"* test is [`KvServiceTest.threeConcurrentClientsIncrementCounter`](src/test/java/com/kvstore/service/KvServiceTest.java) — 300 increments split across 3 threads, expects exactly `counter=300, version=300`.

---

## Project layout

```
kv-store/
├── src/main/java/com/kvstore/
│   ├── KvStoreApplication.java          ← Spring Boot entry point
│   ├── exception/                       ← KvStoreException hierarchy (status-coded)
│   ├── model/KvEntry.java               ← (value, version) tuple
│   ├── service/KvService.java           ← per-key atomic store (framework-agnostic)
│   ├── router/KeyRouter.java            ← hash-mod routing logic (framework-agnostic)
│   ├── http/RequestLoggingFilter.java   ← one INFO log per request/response
│   ├── rest/
│   │   ├── KvController.java            ← @Profile("node")   /kv/*
│   │   ├── RouterController.java        ← @Profile("router") /kv/* (proxy + fan-out)
│   │   └── GlobalExceptionHandler.java  ← @RestControllerAdvice, maps to status codes
│   └── util/JacksonUtil.java
├── docs/ROADMAP.md                      ← Part 3 design (replication + consistent hash)
├── run-node.sh / run-router.sh / run-cluster.sh
└── pom.xml
```

---

## Key design decisions (and why)

- **Domain layer is framework-agnostic.** `KvService` and `KeyRouter` carry no Spring annotations. They're wired by `@Bean` methods in `KvStoreApplication`, each `@Profile`-gated. Means the domain code is unit-testable without booting Spring (see `KvServiceTest`, `KeyRouterTest`).

- **One JAR, two modes (Spring profiles).** Same artifact runs as a node or a router; `--spring.profiles.active=node|router` decides which `@Profile`-gated controller binds the `/kv` routes. No DI conflict, no second main class.

- **Typed exceptions for status codes.** `BadRequestException`, `VersionConflictException`, `NodeUnreachableException` each declare their `httpStatus()`. The HTTP layer does one `catch (KvStoreException e) → e.httpStatus()` — no per-controller wiring.

- **Optimistic concurrency, no locks held across HTTP boundaries.** All locks are taken inside `KvService.save()` and released before the response is built. The HTTP thread never blocks on another HTTP thread.

- **PUT/PATCH return the snapshot from inside `compute()`**, not a fresh `get()` after the write. Otherwise the response could describe a later writer's value (no functional bug, but a confusing contract).

- **NDJSON for listings.** Streaming-friendly; the router can concatenate node responses with no JSON-array bookkeeping.

- **Static hash modulo (not consistent hashing).** Deliberate Part 2 simplification — see [docs/ROADMAP.md](docs/ROADMAP.md) for why and what the upgrade path looks like.

---

## What this isn't (yet)

- **No persistence.** Restart = empty store. Orthogonal to the multi-node story; easy enough to add WAL+snapshots to a single node without changing anything else.
- **No replication.** Any node loss = data loss for that node's keys. The roadmap proposal addresses this directly with N-way replication and quorum reads/writes.
- **Static cluster membership.** Nodes can't join/leave at runtime. Today's `--kvstore.nodes=…` argument is fixed at boot.
- **No auth.** Out of scope.

See [docs/ROADMAP.md](docs/ROADMAP.md) for the proposal that resolves the first two. That doc also commits to a **CP** (consistency-over-availability) model under partition — the rationale is `ifVersion`-style CAS, and the implications run through every failure mode in that file.
