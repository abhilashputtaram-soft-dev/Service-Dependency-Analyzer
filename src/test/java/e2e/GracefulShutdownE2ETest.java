package e2e;

import com.defendermate.graph.graph.GraphStore;
import com.defendermate.graph.ingestion.EventProcessor;
import com.defendermate.graph.ingestion.EventQueueManager;
import com.defendermate.graph.ingestion.EventRequest;
import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.EventType;
import com.defendermate.graph.persistence.GraphSnapshot;
import com.defendermate.graph.persistence.SnapshotManager;
import com.defendermate.graph.persistence.SnapshotProperties;
import com.defendermate.graph.telemetry.TelemetryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the graceful-shutdown and snapshot-persistence lifecycle.
 *
 * <p>Each test exercises a complete snapshot cycle that mirrors a real production restart:
 * <ol>
 *   <li><b>Active ingestion</b> — events are published through the real HTTP API and
 *       processed by the live consumer thread.</li>
 *   <li><b>Queue drain</b> — the test waits until the ingestion queue is empty, proving
 *       every in-flight event was consumed before the snapshot was taken.</li>
 *   <li><b>Graceful shutdown (save)</b> — {@link SnapshotManager#saveSnapshot()} is
 *       called; this is exactly the method invoked by Spring's {@code @PreDestroy}
 *       lifecycle callback.</li>
 *   <li><b>Snapshot verification</b> — the snapshot file exists on disk and its JSON
 *       content reflects the in-memory graph state at shutdown time.</li>
 *   <li><b>Simulated restart (restore)</b> — all in-memory state is wiped, then
 *       {@link SnapshotManager#restoreFromSnapshot()} is called; this mirrors the
 *       {@code @PostConstruct} startup path on a fresh JVM.</li>
 *   <li><b>State verification</b> — the restored graph matches the pre-shutdown state
 *       exactly: same edges, same metrics, no phantom or missing entries.</li>
 * </ol>
 *
 * <h3>Profile configuration</h3>
 * <p>This test inherits {@link BaseE2ETest}'s {@code e2e} Spring profile (which sets
 * {@code snapshot.enabled=false} and reduces queue/thread counts for speed).  A
 * {@link DynamicPropertySource} overrides the three snapshot-specific properties with
 * higher precedence than profile files, enabling real persistence for these tests
 * while keeping all other {@code e2e} settings intact.
 *
 * <p>The snapshot is written to a dedicated temporary file that is deleted after each
 * test method.
 */
@Tag("e2e")
@DisplayName("Graceful Shutdown and Snapshot Persistence")
class GracefulShutdownE2ETest extends BaseE2ETest {

    /** Dedicated snapshot path for this test class; resides in the OS temp directory. */
    private static final String SNAPSHOT_PATH =
            System.getProperty("java.io.tmpdir") + "/graceful-shutdown-e2e-test.json";

    /**
     * Overrides snapshot properties at highest precedence.
     *
     * <p>{@code @DynamicPropertySource} (registered as a
     * {@link org.springframework.context.annotation.Bean}-level override) is resolved
     * <em>after</em> profile-specific property files in Spring Boot's precedence chain,
     * so {@code snapshot.enabled=true} here shadows the {@code e2e} profile's
     * {@code snapshot.enabled=false} without touching any other settings.
     */
    @DynamicPropertySource
    static void snapshotProperties(DynamicPropertyRegistry registry) {
        registry.add("snapshot.enabled",          () -> "true");
        registry.add("snapshot.path",             () -> SNAPSHOT_PATH);
        registry.add("snapshot.interval-seconds", () -> "0"); // no background saves
    }

    // -------------------------------------------------------------------------
    // Injected beans
    // -------------------------------------------------------------------------

    @Autowired private SnapshotManager    snapshotManager;
    @Autowired private SnapshotProperties snapshotProperties;
    @Autowired private GraphStore         graphStore;
    @Autowired private TelemetryStore     telemetryStore;
    @Autowired private EventProcessor     eventProcessor;
    @Autowired private EventQueueManager  queueManager;
    @Autowired private ObjectMapper       objectMapper;

    // -------------------------------------------------------------------------
    // Teardown
    // -------------------------------------------------------------------------

    /**
     * Removes the snapshot file produced during each test so that one test's snapshot
     * cannot influence another test's restore call.
     */
    @AfterEach
    void cleanupSnapshotFile() throws Exception {
        Files.deleteIfExists(Path.of(SNAPSHOT_PATH));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void publishObserve(String source, String target) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.DEPENDENCY_OBSERVED, source, target, null, null, null));
    }

    private void publishObserveWithLatency(String source, String target, long latencyMs) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.DEPENDENCY_OBSERVED, source, target, latencyMs, null, null));
    }

    private void awaitEdgeCount(int expected) {
        awaitAsserted(
                () -> assertThat(graphStore.edgeCount()).isEqualTo(expected),
                Duration.ofSeconds(5));
    }

    private void awaitEdgePresent(String source, String target) {
        awaitAsserted(
                () -> assertThat(graphStore.getEdge(source, target)).isPresent(),
                Duration.ofSeconds(5));
    }

    /**
     * Clears all in-memory graph, telemetry, and deduplication state.
     *
     * <p>This is the test equivalent of a clean application restart: every node, edge,
     * telemetry record, and processed-event-ID is discarded so that a subsequent
     * {@link SnapshotManager#restoreFromSnapshot()} call starts from a truly empty slate.
     */
    private void clearInMemoryState() {
        Set<String> nodes = new HashSet<>(graphStore.getAllServices());
        graphStore.getAllEdges().forEach(e -> {
            nodes.add(e.getSource());
            nodes.add(e.getTarget());
        });
        nodes.forEach(graphStore::removeService);
        telemetryStore.clear();
        eventProcessor.clearProcessedEventIds();
    }

    // =========================================================================
    // Consumer Drain and No Event Loss
    // =========================================================================

    @Nested
    @DisplayName("Consumer Drain and No Event Loss")
    class ConsumerDrainTests {

        /**
         * Publishes 15 events in rapid succession and verifies that:
         * <ul>
         *   <li>The ingestion queue reaches zero size (consumer drained all events).</li>
         *   <li>Every published edge is present in {@link GraphStore} — no silent loss.</li>
         *   <li>A snapshot taken immediately after captures the complete graph.</li>
         * </ul>
         */
        @Test
        @DisplayName("queue drains to zero and all 15 published edges are present at shutdown time")
        void allPublishedEventsAreProcessedBeforeShutdownSnapshot() {
            for (int i = 0; i < 15; i++) {
                publishObserve("drain-" + i, "drain-sink");
            }
            awaitEmptyQueue(Duration.ofSeconds(10));

            assertThat(queueManager.size()).isZero();
            assertThat(graphStore.edgeCount()).isEqualTo(15);

            snapshotManager.saveSnapshot();

            assertThat(Path.of(SNAPSHOT_PATH)).exists();
        }

        /**
         * Five concurrent producer threads each publish four events for distinct edge
         * pairs (20 events total).  After all publishers finish and the queue drains,
         * every event must be reflected in the graph — the single consumer thread must
         * handle the interleaved queue without losing or double-applying any event.
         */
        @Test
        @DisplayName("20 events from 5 concurrent producers are all processed with no event loss")
        void concurrentProducersDoNotLoseEventsBeforeShutdown() {
            int threadCount   = 5;
            int edgesPerThread = 4;
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int e = 0; e < edgesPerThread; e++) {
                        publishObserve("prod-" + threadId + "-" + e, "prod-sink");
                    }
                }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            awaitEmptyQueue(Duration.ofSeconds(10));

            assertThat(queueManager.size()).isZero();
            assertThat(graphStore.edgeCount()).isEqualTo(threadCount * edgesPerThread);

            snapshotManager.saveSnapshot();
            assertThat(Path.of(SNAPSHOT_PATH)).exists();
        }
    }

    // =========================================================================
    // Snapshot File Creation
    // =========================================================================

    @Nested
    @DisplayName("Snapshot File Creation")
    class SnapshotFileTests {

        /**
         * {@link SnapshotManager#saveSnapshot()} must write a non-empty file to the
         * path specified by {@code snapshot.path}.  This is the same path read back
         * by the restore flow on the next startup.
         */
        @Test
        @DisplayName("saveSnapshot() creates a non-empty file at the configured snapshot path")
        void shutdownCreatesSnapshotFileAtConfiguredPath() {
            publishObserve("file-svc-A", "file-svc-B");
            awaitEdgePresent("file-svc-A", "file-svc-B");

            snapshotManager.saveSnapshot();

            Path snapshotFile = Path.of(snapshotProperties.getPath());
            assertThat(snapshotFile).exists();
            assertThat(snapshotFile.toFile().length()).isGreaterThan(0L);
        }

        /**
         * Deserializes the snapshot JSON and verifies that the {@code edges} list
         * contains exactly the number of edges present in the graph at save time,
         * and that the {@code capturedAt} timestamp is populated.
         */
        @Test
        @DisplayName("snapshot JSON contains the correct edge count and a capturedAt timestamp")
        void snapshotFileContainsExpectedEdgeCountAndTimestamp() throws Exception {
            int edgeCount = 8;
            for (int i = 0; i < edgeCount; i++) {
                publishObserve("snap-src-" + i, "snap-sink");
            }
            awaitEdgeCount(edgeCount);

            snapshotManager.saveSnapshot();

            GraphSnapshot persisted =
                    objectMapper.readValue(Path.of(SNAPSHOT_PATH).toFile(), GraphSnapshot.class);
            assertThat(persisted.getEdges()).hasSize(edgeCount);
            assertThat(persisted.getCapturedAt()).isNotNull();
        }
    }

    // =========================================================================
    // State Restoration After Restart
    // =========================================================================

    @Nested
    @DisplayName("State Restoration After Restart")
    class StateRestoreTests {

        /**
         * Full shutdown–restart lifecycle with 10 edges.
         *
         * <ol>
         *   <li>Build a known set of 10 directed edges via the live ingestion API.</li>
         *   <li>Take snapshot ({@code shutdown}).</li>
         *   <li>Wipe all in-memory state ({@code restart}).</li>
         *   <li>Restore from snapshot ({@code startup}).</li>
         *   <li>Assert every pre-shutdown edge is present again — neither missing nor
         *       duplicated.</li>
         * </ol>
         */
        @Test
        @DisplayName("all 10 edges are fully restored and match the pre-shutdown state exactly")
        void restoredGraphMatchesPreshutdownStateExactly() {
            for (int i = 0; i < 10; i++) {
                publishObserve("rs-src-" + i, "rs-sink");
            }
            awaitEdgeCount(10);
            int preShutdownCount = graphStore.edgeCount();

            snapshotManager.saveSnapshot();

            clearInMemoryState();
            assertThat(graphStore.edgeCount()).isZero();

            snapshotManager.restoreFromSnapshot();

            assertThat(graphStore.edgeCount()).isEqualTo(preShutdownCount);
            for (int i = 0; i < 10; i++) {
                assertThat(graphStore.getEdge("rs-src-" + i, "rs-sink"))
                        .as("edge rs-src-%d → rs-sink must be present after restore", i)
                        .isPresent();
            }
            // Internal consistency: flat list and edge-count API must agree
            assertThat(graphStore.getAllEdges().size()).isEqualTo(graphStore.edgeCount());
        }

        /**
         * Edge metrics accumulated across multiple observations — {@code requestCount}
         * and {@code avgLatencyMs} — must survive the snapshot–restore cycle without
         * rounding errors or zeroing.
         *
         * <pre>
         * OBSERVE( 60 ms)  → count=1, avg= 60.0 ms
         * OBSERVE(100 ms)  → count=2, avg= 80.0 ms
         * OBSERVE(140 ms)  → count=3, avg=100.0 ms
         * </pre>
         *
         * After restore the edge must carry {@code requestCount=3} and
         * {@code avgLatencyMs=100.0}.
         */
        @Test
        @DisplayName("requestCount=3 and avgLatencyMs=100.0 are preserved across snapshot and restore")
        void restoredEdgesPreserveRequestCountAndAvgLatency() {
            publishObserveWithLatency("metric-src", "metric-tgt", 60);
            publishObserveWithLatency("metric-src", "metric-tgt", 100);
            publishObserveWithLatency("metric-src", "metric-tgt", 140);

            awaitAsserted(() -> {
                Optional<Edge> e = graphStore.getEdge("metric-src", "metric-tgt");
                assertThat(e).isPresent();
                assertThat(e.get().getRequestCount()).isEqualTo(3L);
                assertThat(e.get().getAvgLatencyMs()).isEqualTo(100.0);
            }, Duration.ofSeconds(5));

            snapshotManager.saveSnapshot();

            clearInMemoryState();
            assertThat(graphStore.getEdge("metric-src", "metric-tgt")).isEmpty();

            snapshotManager.restoreFromSnapshot();

            Edge restored = graphStore.getEdge("metric-src", "metric-tgt").get();
            assertThat(restored.getRequestCount()).isEqualTo(3L);
            assertThat(restored.getAvgLatencyMs()).isEqualTo(100.0);
        }

        /**
         * After a snapshot–restore cycle the ingestion pipeline must remain fully
         * operational: new {@code DEPENDENCY_OBSERVED} events published after the
         * restore must be processed and added to the graph alongside the restored edges.
         *
         * <p>This verifies that restore does not corrupt the consumer thread, the queue,
         * or the {@link GraphStore} internal adjacency maps.
         */
        @Test
        @DisplayName("pipeline accepts and processes new events correctly after a snapshot restore")
        void pipelineRemainsFullyFunctionalAfterRestore() {
            publishObserve("post-pre-src", "post-sink");
            awaitEdgePresent("post-pre-src", "post-sink");

            snapshotManager.saveSnapshot();

            clearInMemoryState();
            snapshotManager.restoreFromSnapshot();

            // Publish a new event after restore — the pipeline must accept it
            publishObserve("post-new-src", "post-sink");
            awaitEdgePresent("post-new-src", "post-sink");

            // Both the restored edge and the new edge must coexist
            assertThat(graphStore.getEdge("post-pre-src", "post-sink")).isPresent();
            assertThat(graphStore.getEdge("post-new-src", "post-sink")).isPresent();
            assertThat(graphStore.edgeCount()).isEqualTo(2);
        }

        /**
         * A snapshot taken when the graph contains no edges must restore to an empty
         * graph.  The restore call must not create phantom edges or throw an exception.
         */
        @Test
        @DisplayName("restoring an empty snapshot leaves the graph empty with no phantom edges")
        void emptyGraphSnapshotRestoresEmptyGraph() {
            // BaseE2ETest.setUp() guarantees an empty graph at test start
            assertThat(graphStore.edgeCount()).isZero();

            snapshotManager.saveSnapshot();         // snapshot: 0 edges

            snapshotManager.restoreFromSnapshot();  // restore from 0-edge snapshot

            assertThat(graphStore.edgeCount()).isZero();
            assertThat(graphStore.getAllEdges()).isEmpty();
        }
    }
}
