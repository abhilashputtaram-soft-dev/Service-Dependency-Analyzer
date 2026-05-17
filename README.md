# Service Dependency Analyzer

A Spring Boot service that ingests a real-time stream of service-dependency events, maintains an in-memory directed graph of those relationships, and answers analytical queries — reachability, shortest path, cycle detection, critical service ranking, and per-service health metrics.

> Architecture, design decisions, and trade-offs are documented in [REPORT.md](REPORT.md).

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Build and Run](#2-build-and-run)
3. [API Documentation (Swagger)](#3-api-documentation-swagger)
4. [Event Publishing APIs](#4-event-publishing-apis)
5. [Query APIs](#5-query-apis)
6. [Configuration](#6-configuration)
7. [Persistence Behavior](#7-persistence-behavior)
8. [Testing](#8-testing)
9. [Assumptions and Constraints](#9-assumptions-and-constraints)

---

## 1. Project Overview

Modern microservice environments consist of dozens to hundreds of interdependent services. When a service degrades, engineers need to know immediately:

- Which services does it depend on?
- Which services depend on it and could be impacted?
- Is there a circular dependency creating a failure loop?
- What is the healthiest path between two services?

**Service Dependency Analyzer** solves this by maintaining a live, in-memory directed graph built from a stream of dependency events. The graph is updated continuously by a concurrent ingestion pipeline and queried via a REST API.


---

## 2. Build and Run

### Prerequisites

- Java 21+
- Maven 3.8+

### Build and start (one command)

```bash
mvn clean install && mvn spring-boot:run
```

### Build only (skip tests)

```bash
mvn clean install -DskipTests
```

### Run after building

```bash
mvn spring-boot:run
```

### Expected startup output

```
INFO  ServiceDependencyAnalyzerApplication - Starting ServiceDependencyAnalyzerApplication
INFO  SnapshotManager   - Loading graph snapshot from ./graph-snapshot.json
INFO  EventConsumer     - Starting 2 consumer thread(s)
INFO  ServiceDependencyAnalyzerApplication - Started on port 8082
```

The application is ready when you see `Started ServiceDependencyAnalyzerApplication`. If a snapshot file exists, the graph is pre-populated before the first request is served.

---

## 3. API Documentation (Swagger)

Interactive documentation is available immediately after startup:

| URL | Purpose |
|-----|---------|
| `http://localhost:8082/swagger-ui.html` | Interactive Swagger UI — explore and try every endpoint |
| `http://localhost:8082/v3/api-docs` | Raw OpenAPI 3 JSON specification |

The Swagger UI groups endpoints under three tags: **Graph Queries**, **Health**, and **Ingestion**. Try-it-out is enabled by default.

---

## 4. Event Publishing APIs

### Bulk publish — random events

Generates and enqueues the requested number of random events, distributed across producer threads.

```bash
curl -s -X POST "http://localhost:8082/api/ingestion/publish?count=5000"
```

**Response:**
```json
{
  "requestedCount": 5000,
  "publishedCount": 5000,
  "producerThreads": 2,
  "queueCapacity": 10000,
  "durationMs": 312
}
```

### Single event publish

Enqueues one explicitly defined event. **Required:** `source`, `type`. All other fields are optional.

```bash
POST /api/ingestion/event
Content-Type: application/json
```

#### DEPENDENCY_OBSERVED

```bash
curl -s -X POST "http://localhost:8082/api/ingestion/event" \
  -H "Content-Type: application/json" \
  -d '{
    "type":      "DEPENDENCY_OBSERVED",
    "source":    "checkout-api",
    "target":    "payment-service",
    "latencyMs": 42,
    "status":    "OK"
  }'
```

#### DEPENDENCY_REMOVED

```bash
curl -s -X POST "http://localhost:8082/api/ingestion/event" \
  -H "Content-Type: application/json" \
  -d '{
    "type":   "DEPENDENCY_REMOVED",
    "source": "checkout-api",
    "target": "payment-service"
  }'
```

#### SERVICE_METADATA

```bash
curl -s -X POST "http://localhost:8082/api/ingestion/event" \
  -H "Content-Type: application/json" \
  -d '{
    "type":   "SERVICE_METADATA",
    "source": "payment-service"
  }'
```

#### HEARTBEAT

```bash
curl -s -X POST "http://localhost:8082/api/ingestion/event" \
  -H "Content-Type: application/json" \
  -d '{
    "type":   "HEARTBEAT",
    "source": "auth-service",
    "status": "OK"
  }'
```

**Response** (202 Accepted):
```json
{
  "eventId":   "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "type":      "DEPENDENCY_OBSERVED",
  "source":    "checkout-api",
  "target":    "payment-service",
  "latencyMs": 42,
  "status":    "OK",
  "timestamp": "2026-05-17T10:00:00Z"
}
```

---

## 5. Query APIs

All query endpoints are `GET /api/graph/`.

### `reachable/{service}` — downstream reachability

```bash
curl -s "http://localhost:8082/api/graph/reachable/checkout-api"
```

```json
{
  "sourceService": "checkout-api",
  "totalReachableServices": 3,
  "paths": [
    { "targetService": "payment-service", "path": ["checkout-api", "payment-service"] },
    { "targetService": "fraud-service",   "path": ["checkout-api", "payment-service", "fraud-service"] },
    { "targetService": "bank-gateway",    "path": ["checkout-api", "payment-service", "bank-gateway"] }
  ]
}
```

### `dependents/{service}` — upstream impact analysis

```bash
curl -s "http://localhost:8082/api/graph/dependents/payment-service"
```

```json
{
  "targetService": "payment-service",
  "totalDependents": 2,
  "dependents": [
    { "dependentService": "checkout-api", "path": ["checkout-api", "payment-service"] },
    { "dependentService": "mobile-app",   "path": ["mobile-app",   "payment-service"] }
  ]
}
```

### `shortest-path` — minimum-latency path

Uses Dijkstra's algorithm weighted by `avgLatencyMs`.

```bash
curl -s "http://localhost:8082/api/graph/shortest-path?source=checkout-api&target=bank-gateway"
```

```json
{
  "sourceService":  "checkout-api",
  "targetService":  "bank-gateway",
  "path":           ["checkout-api", "payment-service", "bank-gateway"],
  "totalLatencyMs": 78.5,
  "reachable":      true
}
```

Returns `reachable: false` and an empty path when no route exists.

### `critical-services` — blast-radius ranking

```bash
curl -s "http://localhost:8082/api/graph/critical-services?k=3"
```

```json
{
  "criticalServices": [
    {
      "serviceName":           "auth-service",
      "dependentServiceCount": 5,
      "impactedRequestCount":  982340,
      "affectedDependents":    ["checkout-api", "mobile-app", "admin-api", "reporting", "jobs"]
    }
  ]
}
```

### `cycles` — circular dependency detection

```bash
curl -s "http://localhost:8082/api/graph/cycles"
```

```json
{
  "totalCycles": 1,
  "cycles": [
    { "cycleId": 1, "path": ["service-A", "service-B", "service-C", "service-A"], "cycleLength": 3 }
  ]
}
```

### `health/{service}` — operational health metrics

**Window format:** `<int>m` (minutes) or `<int>h` (hours) — e.g. `5m`, `30m`, `1h`.

```bash
curl -s "http://localhost:8082/api/graph/health/payment-service?window=5m"
```

```json
{
  "service":      "payment-service",
  "p95LatencyMs": 124,
  "failureRate":  0.012,
  "timeoutRate":  0.003
}
```

### Error responses

```json
{
  "timestamp": "2026-05-17T10:00:00Z",
  "status":    400,
  "error":     "Bad Request",
  "message":   "service must not be blank",
  "path":      "/api/graph/reachable/"
}
```

| Status | Cause |
|--------|-------|
| `400` | Blank service name, non-positive `k` or `count`, malformed window, identical source/target |
| `409` | Duplicate `eventId` resubmitted |
| `500` | Snapshot I/O failure or queue processing error |

---

## 6. Configuration

All properties can be overridden via `application.yml`, environment variables, or JVM system properties.

```yaml
server:
  port: 8082

ingestion:
  queue-capacity: 10000      # bounded queue size; producers block when full
  producer-threads: 2        # parallel event-generating threads
  consumer-threads: 2        # parallel queue-draining threads

snapshot:
  enabled: true              # set false to disable snapshotting (e.g. in tests)
  path: src/main/resources/graph-snapshot.json
  interval-seconds: 60       # background snapshot frequency
```

| Property | Default | Description |
|----------|---------|-------------|
| `ingestion.queue-capacity` | `10000` | Max queue depth before producers block |
| `ingestion.producer-threads` | `2` | Parallel event producers |
| `ingestion.consumer-threads` | `2` | Parallel event consumers |
| `snapshot.enabled` | `true` | Enable/disable all snapshotting |
| `snapshot.path` | `src/main/resources/graph-snapshot.json` | Snapshot file path |
| `snapshot.interval-seconds` | `60` | Seconds between background snapshots |
| Telemetry retention | `1h` (hardcoded) | Max age of telemetry records |

---

## 7. Persistence Behavior

### Periodic snapshots

A background scheduler writes the full graph state to `snapshot.path` every `snapshot.interval-seconds` seconds. Writes are atomic: data goes to a `.tmp` file first, then is renamed over the target to prevent partial reads.

### Graceful shutdown

On SIGTERM or `Ctrl+C`, Spring's `@PreDestroy` hooks run in order:

1. **EventConsumer** — stops consumer threads, drains remaining events (up to 10 s), processes any residual queue entries synchronously.
2. **SnapshotManager** — writes a final snapshot of the fully drained graph.

No in-flight events are lost during a graceful shutdown.

### Startup restoration

On startup, `SnapshotManager` reads the snapshot file (if present) and restores edges, metadata, telemetry, and processed event IDs before the application accepts traffic.

---

## 8. Testing

**300 tests** across unit and E2E categories.

### Commands

```bash
mvn test          # unit tests only (74 tests, no Spring context)
mvn verify        # full suite: unit + E2E (300 tests, real Spring Boot context)
mvn verify site   # full suite + styled HTML reports at target/site/index.html
mvn verify -Pe2e-only   # E2E tests only, Surefire skipped
```

### Test suites

| Suite | Category | Coverage |
|-------|----------|----------|
| `GraphStoreTest` | Unit | Edge CRUD, concurrency |
| `GraphQueryServiceTest` | Unit | All 6 query algorithms |
| `GraphStateManagerTest` | Unit | Event dispatch, dedup, metadata |
| `TelemetryStoreTest` | Unit | Windowing, p95, eviction |
| `IngestionPipelineE2ETest` | E2E | Bulk publish, concurrent producers, queue drain |
| `GraphQueryE2ETest` | E2E | All 6 query APIs over HTTP |
| `HealthQueryE2ETest` | E2E | Health metrics, time windows, error cases |
| `OutOfOrderEventE2ETest` | E2E | Remove-before-add, duplicates, concurrent ordering |
| `GracefulShutdownE2ETest` | E2E | Consumer drain, snapshot lifecycle, state restore |

### HTML reports

After `mvn verify`:

| Report | Path |
|--------|------|
| Unit tests | `target/site/surefire-report.html` |
| E2E tests | `target/site/failsafe-report.html` |

---

## 9. Assumptions and Constraints

### Assumptions

- **All state is in-memory.** A restart without a snapshot file starts from an empty graph.
- **Eventual consistency is accepted.** The queue introduces a short lag between publication and graph visibility; queries always reflect the current committed state.
- **Duplicate events are idempotent-safe.** Re-submitted `eventId`s are rejected (`409 Conflict`) without mutating the graph.
- **`DEPENDENCY_REMOVED` is a safe no-op when the edge doesn't exist.** Out-of-order removes are silently ignored; a subsequent `DEPENDENCY_OBSERVED` for the same pair creates the edge normally.
- **No distributed coordination.** Single in-process graph; no sharding or leader election.
- **Service names are opaque strings.** No validation beyond non-blank.

### Constraints enforced

- **Bounded queue with back-pressure.** The queue has a hard capacity ceiling; producers block rather than dropping events or growing without bound.
- **Atomic snapshot writes.** Snapshots are never written in-place — always via temp-file rename — so a mid-write crash cannot corrupt the last good snapshot.
- **Consumer drain on shutdown.** The application will not write its final snapshot until the queue is empty (or the 10 s drain timeout expires), ensuring the persisted state reflects all processed events.
- **Telemetry bounded at 1 h.** Records are evicted lazily on write; health queries are limited to a maximum 1-hour look-back.


