# Service Dependency Analyzer — Design Report

A Spring Boot service that ingests a real-time stream of service-dependency events, maintains an in-memory directed graph of those relationships, and answers analytical queries — reachability, shortest path, cycle detection, critical service ranking, and per-service health metrics.

---

## Architecture Overview

```
HTTP Clients
     │
     ▼
┌──────────────────────────────────────────────────────────────┐
│  REST Layer (Spring MVC)                                     │
│  IngestionController  │  GraphQueryController                │
└───────────┬──────────────────────┬───────────────────────────┘
            │                      │ (read-only)
            ▼                      ▼
┌──────────────────┐    ┌──────────────────────────────────────┐
│  Ingestion Queue │    │  GraphQueryService                   │
│  LinkedBlocking  │    │  reachability · shortest-path        │
│  Queue (bounded) │    │  cycles · critical-services          │
└────────┬─────────┘    │  dependents · health                 │
         │              └──────────────┬───────────────────────┘
         │ (drain)                     │
         ▼                             │
┌──────────────────────────────────────┴───────────────────────┐
│  GraphStateManager   (event dispatch + dedup)                │
│  EventProcessor      (per-type mutation logic)               │
└───────────────────────────────────────┬──────────────────────┘
                                        │
               ┌────────────────────────┴──────────────────┐
               ▼                                           ▼
        ┌─────────────┐                          ┌─────────────────┐
        │  GraphStore │                          │  TelemetryStore │
        │  edges      │                          │  latency / error│
        │  metadata   │                          │  records (1 h)  │
        └──────┬──────┘                          └─────────────────┘
               │
        ┌──────▼──────┐
        │SnapshotMgr  │
        │ periodic +  │
        │ shutdown    │
        └─────────────┘
```

---

## Core concepts

- **Nodes** are services identified by name (e.g. `checkout-api`, `payment-service`).
- **Edges** are directed dependency relationships: `source → target` means *source calls target*. Edges accumulate latency and request/error counts from observed events.
- **Events** are the unit of input: `DEPENDENCY_OBSERVED` adds or updates an edge, `DEPENDENCY_REMOVED` removes one, `SERVICE_METADATA` enriches node metadata, and `HEARTBEAT` records liveness.
- **Graph State** is the live, mutable view of the dependency graph held in a `ConcurrentHashMap<String, ConcurrentHashMap<String, Edge>>` adjacency structure — outer key is source service, inner key is target service. Mutations (edge add/remove, metadata upsert) are applied by consumer threads; reads by query handlers traverse the map without additional locking.
- **Telemetry Store** is a per-service time-series buffer of individual event records (latency, status, timestamp). Records older than 1 hour are evicted lazily on write. Query handlers scan the window-filtered records to compute p95 latency, failure rate, and timeout rate on demand.

Events arrive at the REST API, are enqueued into a bounded `LinkedBlockingQueue`, and are drained asynchronously by consumer threads that apply mutations to the graph. Queries read the graph directly — readers never block writers.

---

## Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Runtime | Java 21, Spring Boot 3.2.5 | Records + virtual thread readiness; production-grade DI/lifecycle |
| Web | Spring MVC (Servlet) | Synchronous HTTP well-matched to query workload |
| Ingestion pipeline | `LinkedBlockingQueue` + `ExecutorService` | Built-in JDK; bounded; blocking semantics give back-pressure for free |
| Graph store | `ConcurrentHashMap` (adjacency) | O(1) edge lookup; segment-level locking enables concurrent reads without a global lock |
| Persistence | Jackson JSON + atomic rename | Zero additional dependencies; consistent snapshot via tmp-file-rename |
| API docs | springdoc-openapi 2.5.0 | Auto-generates OpenAPI 3 from annotations; compatible with Spring Boot 3 |
| Testing | JUnit 5, Spring Boot Test, Maven Surefire + Failsafe | Standard; Surefire for unit, Failsafe for Spring-context E2E |

---

## Features

| Feature | Description |
|---------|-------------|
| **Concurrent ingestion** | Events arrive over HTTP, are queued into a bounded `LinkedBlockingQueue`, and consumed by a configurable thread pool — decoupling ingest rate from processing rate |
| **Directed dependency graph** | Edges are weighted with accumulated latency and request/error counts derived from live telemetry |
| **Reachability (BFS)** | Full downstream traversal with paths from a source service |
| **Dependents (reverse BFS)** | All upstream callers of a service — the "blast radius" view |
| **Shortest path (Dijkstra)** | Minimum average-latency path between two services |
| **Cycle detection (DFS)** | Identifies circular dependency chains |
| **Critical services ranking** | Top-k services by dependent count, tiebroken by total request volume |
| **Health metrics** | Per-service p95 latency, failure rate, and timeout rate over a configurable time window |
| **Graceful shutdown** | Consumer drain → final snapshot; no in-flight events lost |
| **Startup restore** | Previous graph state is available before the first request |
| **Atomic snapshots** | Writes via temp-file rename; no partial-read exposure |
| **Duplicate suppression** | `eventId`-based deduplication; re-submissions get `409 Conflict` |
| **Out-of-order safety** | `DEPENDENCY_REMOVED` before `DEPENDENCY_OBSERVED` is a no-op; the later add creates the edge normally |

---

## Design Decisions

### Queue model

A bounded `LinkedBlockingQueue` was chosen over an unbounded queue or a direct synchronous call for three reasons: (1) it decouples HTTP latency from graph-mutation latency; (2) the capacity ceiling converts overflow into back-pressure on the caller rather than heap exhaustion; (3) `BlockingQueue.drainTo()` makes graceful-shutdown drain a single, race-free operation.

### Graph representation

The graph is a `ConcurrentHashMap<String, ConcurrentHashMap<String, Edge>>`. The outer map is keyed by source service name; the inner map by target. This gives O(1) edge lookup and mutation with no global lock. Reads (queries) traverse the map without any additional synchronisation — `ConcurrentHashMap` guarantees visibility of completed puts.

### Concurrency strategy

Producer threads enqueue events concurrently. A fixed consumer pool drains the queue and routes each event to `EventProcessor`, which applies the mutation to `GraphStore`. GraphStore methods that update a service's adjacency entry use `ConcurrentHashMap.compute()` for atomic edge upserts, keeping critical sections as short as possible and avoiding coarse-grained synchronization.

### Persistence approach

Persistence uses a snapshot rather than a write-ahead log: every 60 seconds (configurable), and once more during shutdown, the full graph is serialized to JSON and written to a `.tmp` file, which is then atomically renamed over the live snapshot. This approach eliminates the complexity of WAL replay at the cost of accepting data loss between the last snapshot and a hard kill. The trade-off is acceptable for an assignment-scale system; a production system would use a WAL or an event-sourced store.

---

## Criticality Metric

A service is ranked *more critical* if a higher number of distinct services directly depend on it — because that count directly measures the blast radius of a failure: one outage, many callers affected.

**Primary key:** direct dependent count (in-degree in the dependency graph).  
**Tiebreaker:** total request volume attributed to dependents of that service.

The tiebreaker adds a traffic-weighted dimension: two services with identical dependent counts are more or less critical depending on how much real traffic flows through them. A service with 3 low-traffic dependents is ranked lower than one with 3 high-throughput dependents, even though they share the same in-degree.

---

## Trade-offs

| Choice | Benefit | Cost |
|--------|---------|------|
| In-memory graph | Zero infrastructure; fast reads | Lost on hard kill; single-JVM |
| Snapshot vs WAL | Simple implementation; no replay logic | Data loss up to `interval-seconds` on crash |
| Bounded queue + back-pressure | Prevents OOM; explicit capacity contract | Callers block when queue is full |
| HTTP API vs Kafka | Self-contained; easy to demo | No replay, no consumer-group scaling |
| Hardcoded 1 h telemetry retention | Bounded memory; simple eviction | Health queries capped at 1 h; not configurable |
| Single node | No coordination overhead | No horizontal scale; graph limited to heap |

---

## Future Improvements

| Area | Improvement |
|------|-------------|
| **Durable ingestion** | Replace HTTP bulk-publish with a Kafka consumer for replay, consumer-group scaling, and at-least-once delivery |
| **Graph database** | Migrate to Neo4j (or an event-sourced store) to eliminate data loss and enable richer traversal queries |
| **Distributed graph** | Partition the graph across nodes with consistent hashing, or use a shared in-memory store (Redis Graph) |
| **Observability** | Expose Micrometer metrics (queue depth, processing latency, graph node/edge count) to Prometheus/Grafana |
| **Query caching** | Cache expensive traversal results (reachability, cycles) with time-bounded invalidation |
| **Configurable retention** | Expose `telemetry.retention-hours` as an externalized property instead of a compile-time constant |
| **Authentication** | Add Spring Security with JWT or API-key enforcement |
| **Pagination** | Add cursor-based pagination to reachability and dependents responses for large graphs |

---

## Reflections

### What went well

The concurrent ingestion pipeline came together cleanly. The separation between the HTTP layer (enqueue-and-return), the consumer pool (drain and mutate), and the query layer (lock-free reads) made each concern independently testable and kept the code simple to reason about. The graceful-shutdown sequence — drain the queue first, snapshot second — required careful ordering of `@PreDestroy` hooks and proved correct under the full E2E test suite, including a dedicated `GracefulShutdownE2ETest` that verifies snapshot content and the full save → clear → restore lifecycle.

The out-of-order event handling was an early design decision that paid off: treating `DEPENDENCY_REMOVED` as a no-op when no edge exists meant none of the E2E tests needed to enforce strict ordering, which better mirrors real event-stream conditions.

The 300-test suite (74 unit + 226 E2E) with full Spring Boot context for E2E tests, `@DynamicPropertySource` for per-test snapshot isolation, and separate Surefire/Failsafe phases gives high confidence in regressions without sacrificing unit-test speed.

### What I would do differently

**Telemetry retention is hardcoded.** The `MAX_RETENTION = Duration.ofHours(1)` constant in `TelemetryStore` was a deliberate simplification, but it means the health window cap is invisible to operators and cannot be tuned without a recompile. Externalizing it as `telemetry.retention-hours` would be the first change in a production hardening pass.

**Snapshot-only persistence.** The atomic-rename approach is correct and crash-safe for the snapshot itself, but the gap between snapshots means a hard kill can lose up to `interval-seconds` of accepted events. For any production use this would need to become a WAL or be replaced by Kafka offset commits, so that "accepted by the API" implies durable.
