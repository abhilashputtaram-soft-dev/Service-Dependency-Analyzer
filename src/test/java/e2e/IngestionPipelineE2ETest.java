package e2e;

import com.defendermate.graph.graph.GraphStore;
import com.defendermate.graph.ingestion.EventQueueManager;
import com.defendermate.graph.ingestion.EventRequest;
import com.defendermate.graph.ingestion.IngestionSummary;
import com.defendermate.graph.model.Event;
import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.EventType;
import com.defendermate.graph.telemetry.TelemetryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the ingestion pipeline.
 *
 * <p>Starts the full Spring Boot application (no mocks). Each test begins with an
 * empty in-memory graph, an empty telemetry store, and an empty processed-event-ID
 * set — guaranteed by the {@code @BeforeEach} reset in {@link BaseE2ETest}.
 *
 * <p>Tag: {@code e2e} — run individually with {@code mvn test -Dgroups=e2e}.
 */
@Tag("e2e")
@DisplayName("Ingestion Pipeline")
class IngestionPipelineE2ETest extends BaseE2ETest {

    @Autowired
    private GraphStore graphStore;

    @Autowired
    private TelemetryStore telemetryStore;

    @Autowired
    private EventQueueManager queueManager;

    // ==========================================================================
    // Ingestion Summary Response
    // ==========================================================================

    @Nested
    @DisplayName("Ingestion Summary Response")
    class IngestionSummaryTests {

        @Test
        @DisplayName("published count equals requested count when queue has capacity")
        void publishedCountEqualsRequestedCount() {
            ResponseEntity<IngestionSummary> response = ingestionApi.publish(100);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            IngestionSummary body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getRequestedCount()).isEqualTo(100);
            assertThat(body.getPublishedCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("summary reflects active pipeline configuration from e2e profile")
        void summaryReflectsQueueConfiguration() {
            ResponseEntity<IngestionSummary> response = ingestionApi.publish(10);
            IngestionSummary body = response.getBody();

            assertThat(body).isNotNull();
            assertThat(body.getProducerThreads()).isGreaterThanOrEqualTo(1);
            assertThat(body.getQueueCapacity()).isEqualTo(500);   // application-e2e.properties
            assertThat(body.getDurationMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("count=0 is rejected with 400 Bad Request")
        void zeroCountReturnsBadRequest() {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/api/ingestion/publish?count=0", null, String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ==========================================================================
    // Graph State After Ingestion
    // ==========================================================================

    @Nested
    @DisplayName("Graph State After Ingestion")
    class GraphStateMutationTests {

        @Test
        @DisplayName("DEPENDENCY_OBSERVED event creates exactly one directed edge")
        void dependencyObservedCreatesEdge() {
            EventRequest req = new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "checkout-api", "payment-service",
                    null, null, null);

            ResponseEntity<Event> response = ingestionApi.publishEvent(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            Event published = response.getBody();
            assertThat(published).isNotNull();
            assertThat(published.getSource()).isEqualTo("checkout-api");
            assertThat(published.getTarget()).isEqualTo("payment-service");
            assertThat(published.getEventId()).isNotBlank();  // server assigned a UUID

            awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(1), Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("DEPENDENCY_REMOVED event deletes the edge from the graph")
        void dependencyRemovedDeletesEdge() {
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "svc-alpha", "svc-beta",
                    null, null, null));
            awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(1), Duration.ofSeconds(5));

            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_REMOVED, "svc-alpha", "svc-beta",
                    null, null, null));
            awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(0), Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("duplicate eventId is deduplicated — second event has no effect")
        void duplicateEventIdIsDeduped() {
            String eventId = UUID.randomUUID().toString();
            EventRequest req = new EventRequest(
                    eventId, EventType.DEPENDENCY_OBSERVED, "svc-dup-src", "svc-dup-tgt",
                    null, null, null);

            ingestionApi.publishEvent(req);
            ingestionApi.publishEvent(req);   // identical eventId → must be dropped

            // Wait for both events to be dequeued and processed
            awaitEmptyQueue();
            awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(1), Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("three distinct events create three independent edges")
        void multipleDistinctEventsCreateMultipleEdges() {
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "svc-1", "svc-2", null, null, null));
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "svc-2", "svc-3", null, null, null));
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "svc-1", "svc-3", null, null, null));

            awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(3), Duration.ofSeconds(5));
        }
    }

    // ==========================================================================
    // Telemetry Recording
    // ==========================================================================

    @Nested
    @DisplayName("Telemetry Recording")
    class TelemetryRecordingTests {

        @Test
        @DisplayName("HEARTBEAT with latency and status stores one telemetry record")
        void heartbeatWithLatencyStoresTelemetry() {
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.HEARTBEAT, "telemetry-svc", null, 42L, EventStatus.OK, null));

            awaitAsserted(
                    () -> assertThat(telemetryStore.snapshotAll()).containsKey("telemetry-svc"),
                    Duration.ofSeconds(5));
            assertThat(telemetryStore.snapshotAll().get("telemetry-svc")).hasSize(1);
        }

        @Test
        @DisplayName("DEPENDENCY_OBSERVED with latency and status stores a telemetry record")
        void dependencyObservedWithTelemetryFieldsStoresTelemetry() {
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "src-with-telemetry", "tgt-svc",
                    120L, EventStatus.OK, null));

            awaitAsserted(
                    () -> assertThat(telemetryStore.snapshotAll()).containsKey("src-with-telemetry"),
                    Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("event without latency does not produce a telemetry record")
        void eventWithoutLatencyDoesNotStoreTelemetry() {
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "no-latency-svc", "target",
                    null, null, null));

            // Wait for the edge to appear as proof the event was fully processed
            awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(1), Duration.ofSeconds(5));

            // Telemetry must be absent since latencyMs was null
            assertThat(telemetryStore.snapshotAll()).doesNotContainKey("no-latency-svc");
        }

        @Test
        @DisplayName("five heartbeats accumulate five telemetry records for the same service")
        void multipleHeartbeatsAccumulateTelemetry() {
            for (int i = 0; i < 5; i++) {
                ingestionApi.publishEvent(new EventRequest(
                        null, EventType.HEARTBEAT, "multi-beat-svc", null,
                        (long) (10 + i * 5), EventStatus.OK, null));
            }

            awaitAsserted(
                    () -> assertThat(telemetryStore.snapshotAll()
                            .getOrDefault("multi-beat-svc", List.of())).hasSize(5),
                    Duration.ofSeconds(5));
        }
    }

    // ==========================================================================
    // Bulk Ingestion (1 000 events)
    // ==========================================================================

    @Nested
    @DisplayName("Bulk Ingestion")
    class BulkIngestionTests {

        @Test
        @DisplayName("1000 events are published and the queue drains completely")
        void bulk1000EventsQueueDrainsCompletely() {
            ResponseEntity<IngestionSummary> response = ingestionApi.publish(1000);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getPublishedCount()).isEqualTo(1000);

            awaitEmptyQueue(Duration.ofSeconds(30));
            assertThat(queueManager.size()).isZero();
        }

        @Test
        @DisplayName("graph has at least one edge after 1000 random events")
        void graphHasEdgesAfterBulkIngestion() {
            ingestionApi.publish(1000);

            awaitEmptyQueue(Duration.ofSeconds(30));
            awaitAsserted(() -> assertThat(graphStore.edgeCount()).isGreaterThan(0),
                    Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("graph data is internally consistent after processing 1000 events")
        void graphIsConsistentAfterBulkIngestion() {
            ingestionApi.publish(1000);

            awaitEmptyQueue(Duration.ofSeconds(30));

            // getAllEdges() and edgeCount() must agree (internal consistency check)
            int reportedCount = graphStore.edgeCount();
            assertThat(graphStore.getAllEdges()).hasSize(reportedCount);

            // Every edge must have non-blank source and target
            graphStore.getAllEdges().forEach(edge -> {
                assertThat(edge.getSource()).isNotBlank();
                assertThat(edge.getTarget()).isNotBlank();
            });
        }
    }

    // ==========================================================================
    // Concurrent Producers and Consumers
    // ==========================================================================

    @Nested
    @DisplayName("Concurrent Producers and Consumers")
    class ConcurrencyTests {

        @Test
        @DisplayName("100 events from 5 concurrent publishers are all processed")
        void concurrentPublishersAllEventsProcessed() throws Exception {
            int threadCount = 5;
            int eventsPerThread = 20;

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                final String source = "concurrent-src-" + t;
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < eventsPerThread; i++) {
                        ingestionApi.publishEvent(new EventRequest(
                                null, EventType.DEPENDENCY_OBSERVED,
                                source, "concurrent-tgt-" + i, null, null, null));
                    }
                }));
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(30, TimeUnit.SECONDS);

            // Each (source, target) pair is unique across all threads → expect exactly 100 edges
            awaitAsserted(
                    () -> assertThat(graphStore.edgeCount()).isEqualTo(threadCount * eventsPerThread),
                    Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("bounded queue (capacity 500) handles 1000 events without data loss")
        void boundedQueueHandlesLoadExceedingCapacity() {
            // Queue capacity is 500 (application-e2e.properties).
            // Producers block on a full queue; consumers drain it concurrently.
            // All 1000 events must be published without error.
            ResponseEntity<IngestionSummary> response = ingestionApi.publish(1000);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getPublishedCount()).isEqualTo(1000);

            awaitEmptyQueue(Duration.ofSeconds(30));

            // Graph integrity must hold after sustained load
            assertThat(graphStore.getAllEdges())
                    .hasSize(graphStore.edgeCount())
                    .allSatisfy(edge -> {
                        assertThat(edge.getSource()).isNotBlank();
                        assertThat(edge.getTarget()).isNotBlank();
                    });
        }
    }
}
