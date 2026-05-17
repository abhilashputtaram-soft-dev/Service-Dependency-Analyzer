package com.defendermate.graph.ingestion;

import com.defendermate.graph.graph.GraphStateManager;
import com.defendermate.graph.model.*;
import com.defendermate.graph.telemetry.TelemetryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventProcessorTest {

    @Mock GraphStateManager graphStateManager;
    @Mock TelemetryStore    telemetryStore;

    private EventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EventProcessor(graphStateManager, telemetryStore);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Event observed(String id, Long latencyMs, EventStatus status) {
        return new Event(id, EventType.DEPENDENCY_OBSERVED, "svc-a", "svc-b", latencyMs, status, Instant.now());
    }

    private Event heartbeat(String id, Long latencyMs, EventStatus status) {
        return new Event(id, EventType.HEARTBEAT, "svc-a", null, latencyMs, status, Instant.now());
    }

    private Event removed(String id) {
        return new Event(id, EventType.DEPENDENCY_REMOVED, "svc-a", "svc-b", null, null, Instant.now());
    }

    private Event metadata(String id) {
        return new Event(id, EventType.SERVICE_METADATA, "svc-a", null, null, null, Instant.now());
    }

    // ==================================================================
    // Input Validation
    // ==================================================================

    @Nested
    class ValidationTests {

        @Test
        void nullType_eventIsSkippedCompletely() {
            Event invalid = new Event("id-1", null, "svc-a", null, null, null, Instant.now());

            processor.process(invalid);

            verifyNoInteractions(graphStateManager, telemetryStore);
        }

        @Test
        void duplicateEventId_secondCallIsDropped() {
            processor.process(observed("dup-id", 100L, EventStatus.OK));
            processor.process(observed("dup-id", 200L, EventStatus.OK));

            verify(graphStateManager, times(1)).processEvent(any());
        }

        @Test
        void nullEventId_noDedupApplied_bothEventsForwarded() {
            Event e1 = new Event(null, EventType.DEPENDENCY_OBSERVED, "svc-a", "svc-b", null, null, Instant.now());
            Event e2 = new Event(null, EventType.DEPENDENCY_OBSERVED, "svc-a", "svc-b", null, null, Instant.now());

            processor.process(e1);
            processor.process(e2);

            verify(graphStateManager, times(2)).processEvent(any());
        }

        @Test
        void downstreamException_isCaughtAndPipelineContinues() {
            doThrow(new RuntimeException("graph exploded")).when(graphStateManager).processEvent(any());

            assertThatNoException().isThrownBy(() -> processor.process(observed("e1", 50L, EventStatus.OK)));
        }
    }

    // ==================================================================
    // Telemetry recording gate
    // ==================================================================

    @Nested
    class TelemetryRecordingTests {

        @Test
        void dependencyObserved_withLatencyAndStatus_recordsTelemetry() {
            processor.process(observed("e1", 120L, EventStatus.OK));

            verify(telemetryStore).record(eq("svc-b"), any());
        }

        @Test
        void dependencyObserved_missingLatency_telemetrySkipped() {
            processor.process(observed("e1", null, EventStatus.OK));

            verifyNoInteractions(telemetryStore);
        }

        @Test
        void dependencyObserved_missingStatus_telemetrySkipped() {
            processor.process(observed("e1", 100L, null));

            verifyNoInteractions(telemetryStore);
        }

        @Test
        void heartbeat_withLatencyAndStatus_recordsTelemetry() {
            processor.process(heartbeat("e1", 10L, EventStatus.OK));

            verify(telemetryStore).record(eq("svc-a"), any());
        }

        @Test
        void dependencyRemoved_telemetryNotRecorded() {
            processor.process(removed("e1"));

            verifyNoInteractions(telemetryStore);
        }

        @Test
        void serviceMetadata_telemetryNotRecorded() {
            processor.process(metadata("e1"));

            verifyNoInteractions(telemetryStore);
        }
    }

    // ==================================================================
    // Deduplication state management
    // ==================================================================

    @Nested
    class DeduplicationStateTests {

        @Test
        void snapshotProcessedEventIds_containsAllProcessedIds() {
            processor.process(observed("alpha", null, null));
            processor.process(metadata("beta"));

            assertThat(processor.snapshotProcessedEventIds()).containsExactlyInAnyOrder("alpha", "beta");
        }

        @Test
        void restoreProcessedEventIds_blocksSubsequentProcessingOfRestoredId() {
            processor.restoreProcessedEventIds(Set.of("pre-seen"));

            processor.process(observed("pre-seen", null, null));

            verifyNoInteractions(graphStateManager);
        }

        @Test
        void restoreProcessedEventIds_null_noException() {
            assertThatNoException().isThrownBy(() -> processor.restoreProcessedEventIds(null));
        }

        @Test
        void clearProcessedEventIds_allowsSameIdToBeReprocessed() {
            processor.process(observed("reuse-id", null, null));
            processor.clearProcessedEventIds();
            processor.process(observed("reuse-id", null, null));

            verify(graphStateManager, times(2)).processEvent(any());
        }
    }
}
