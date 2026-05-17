package com.defendermate.graph.persistence;

import com.defendermate.graph.graph.GraphStateManager;
import com.defendermate.graph.graph.GraphStore;
import com.defendermate.graph.ingestion.EventProcessor;
import com.defendermate.graph.model.Edge;
import com.defendermate.graph.telemetry.TelemetryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class SnapshotManagerTest {

    @TempDir Path tempDir;

    private GraphStore       graphStore;
    private TelemetryStore   telemetryStore;
    private EventProcessor   eventProcessor;
    private SnapshotProperties properties;
    private ObjectMapper     mapper;
    private SnapshotManager  snapshotManager;

    @BeforeEach
    void setUp() {
        graphStore     = new GraphStore();
        telemetryStore = new TelemetryStore();
        eventProcessor = new EventProcessor(new GraphStateManager(graphStore), telemetryStore);

        properties = new SnapshotProperties();
        properties.setEnabled(true);
        properties.setIntervalSeconds(0);  // no background scheduler in unit tests
        properties.setPath(tempDir.resolve("snapshot.json").toString());

        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        snapshotManager = new SnapshotManager(graphStore, telemetryStore, eventProcessor, properties, mapper);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Edge edge(String source, String target) {
        return new Edge(source, target, 50.0, 10L, 0L, Instant.now());
    }

    private SnapshotManager freshManagerWithSameFile() {
        GraphStore       fs = new GraphStore();
        TelemetryStore   ft = new TelemetryStore();
        EventProcessor   fp = new EventProcessor(new GraphStateManager(fs), ft);
        SnapshotManager  sm = new SnapshotManager(fs, ft, fp, properties, mapper);
        return sm;
    }

    // ==================================================================
    // Save
    // ==================================================================

    @Nested
    class SaveTests {

        @Test
        void saveSnapshot_createsFileAtConfiguredPath() {
            snapshotManager.saveSnapshot();

            assertThat(Path.of(properties.getPath())).exists();
        }

        @Test
        void saveSnapshot_fileContainsEdgesFromGraphStore() throws IOException {
            graphStore.putEdge(edge("checkout-api", "payment-service"));

            snapshotManager.saveSnapshot();

            GraphSnapshot loaded = mapper.readValue(Path.of(properties.getPath()).toFile(), GraphSnapshot.class);
            assertThat(loaded.getEdges())
                    .anyMatch(e -> "checkout-api".equals(e.getSource())
                               && "payment-service".equals(e.getTarget()));
        }

        @Test
        void saveSnapshot_tmpFileNotPresentAfterAtomicRename() {
            snapshotManager.saveSnapshot();

            assertThat(Path.of(properties.getPath() + ".tmp")).doesNotExist();
        }

        @Test
        void saveSnapshot_capturesProcessedEventIds() throws IOException {
            eventProcessor.restoreProcessedEventIds(Set.of("evt-001", "evt-002"));

            snapshotManager.saveSnapshot();

            GraphSnapshot loaded = mapper.readValue(Path.of(properties.getPath()).toFile(), GraphSnapshot.class);
            assertThat(loaded.getProcessedEventIds()).containsExactlyInAnyOrder("evt-001", "evt-002");
        }

        @Test
        void shutdown_whenDisabled_doesNotWriteSnapshotFile() {
            properties.setEnabled(false);
            SnapshotManager disabled = new SnapshotManager(
                    graphStore, telemetryStore, eventProcessor, properties, mapper);

            graphStore.putEdge(edge("a", "b"));
            disabled.shutdown();

            assertThat(Path.of(properties.getPath())).doesNotExist();
        }
    }

    // ==================================================================
    // Restore
    // ==================================================================

    @Nested
    class RestoreTests {

        @Test
        void restoreFromSnapshot_populatesGraphStoreWithPersistedEdges() {
            graphStore.putEdge(edge("svc-a", "svc-b"));
            snapshotManager.saveSnapshot();

            // Restore into a fresh set of stores via a new manager pointing to the same file
            GraphStore     fs = new GraphStore();
            TelemetryStore ft = new TelemetryStore();
            EventProcessor fp = new EventProcessor(new GraphStateManager(fs), ft);
            SnapshotManager fresh = new SnapshotManager(fs, ft, fp, properties, mapper);

            fresh.restoreFromSnapshot();

            assertThat(fs.getEdge("svc-a", "svc-b")).isPresent();
        }

        @Test
        void restoreFromSnapshot_multipleEdgesAllRestored() {
            graphStore.putEdge(edge("a", "b"));
            graphStore.putEdge(edge("b", "c"));
            graphStore.putEdge(edge("c", "d"));
            snapshotManager.saveSnapshot();

            GraphStore     fs = new GraphStore();
            TelemetryStore ft = new TelemetryStore();
            EventProcessor fp = new EventProcessor(new GraphStateManager(fs), ft);
            SnapshotManager fresh = new SnapshotManager(fs, ft, fp, properties, mapper);

            fresh.restoreFromSnapshot();

            assertThat(fs.getAllEdges()).hasSize(3);
        }

        @Test
        void restoreFromSnapshot_restoresDeduplicationBarrier() {
            eventProcessor.restoreProcessedEventIds(Set.of("seen-evt"));
            snapshotManager.saveSnapshot();
            eventProcessor.clearProcessedEventIds();

            snapshotManager.restoreFromSnapshot();

            assertThat(eventProcessor.snapshotProcessedEventIds()).contains("seen-evt");
        }

        @Test
        void restoreFromSnapshot_noFile_noExceptionAndGraphRemainsEmpty() {
            properties.setPath(tempDir.resolve("does-not-exist.json").toString());

            assertThatNoException().isThrownBy(() -> snapshotManager.restoreFromSnapshot());
            assertThat(graphStore.getAllEdges()).isEmpty();
        }

        @Test
        void start_whenDisabled_doesNotAttemptFileRead() {
            // Write a file to the path so there is something to read
            graphStore.putEdge(edge("old", "edge"));
            snapshotManager.saveSnapshot();

            // New manager with disabled flag — start() should skip the load
            properties.setEnabled(false);
            GraphStore     fs = new GraphStore();
            TelemetryStore ft = new TelemetryStore();
            EventProcessor fp = new EventProcessor(new GraphStateManager(fs), ft);
            SnapshotManager disabled = new SnapshotManager(fs, ft, fp, properties, mapper);

            disabled.start();

            assertThat(fs.getAllEdges()).isEmpty();
        }
    }

    // ==================================================================
    // Atomicity
    // ==================================================================

    @Nested
    class AtomicityTests {

        @Test
        void consecutiveSaves_overwritePreviousSnapshotCorrectly() throws IOException {
            graphStore.putEdge(edge("first", "snapshot"));
            snapshotManager.saveSnapshot();

            graphStore.putEdge(edge("second", "snapshot"));
            snapshotManager.saveSnapshot();

            GraphSnapshot loaded = mapper.readValue(Path.of(properties.getPath()).toFile(), GraphSnapshot.class);
            assertThat(loaded.getEdges()).hasSize(2);
        }

        @Test
        void snapshotFileContainsCapturedAtTimestamp() throws IOException {
            Instant before = Instant.now();
            snapshotManager.saveSnapshot();

            GraphSnapshot loaded = mapper.readValue(Path.of(properties.getPath()).toFile(), GraphSnapshot.class);
            assertThat(loaded.getCapturedAt()).isAfterOrEqualTo(before);
        }
    }
}
