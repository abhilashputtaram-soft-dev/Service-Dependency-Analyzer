package com.defendermate.graph.graph;

import com.defendermate.graph.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class GraphStateManagerTest {

    private GraphStore store;
    private GraphStateManager manager;

    @BeforeEach
    void setUp() {
        store   = new GraphStore();
        manager = new GraphStateManager(store);
    }

    // ------------------------------------------------------------------
    // Event builder helpers
    // ------------------------------------------------------------------

    private Event observed(String id, String source, String target, Long latency, EventStatus status) {
        return new Event(id, EventType.DEPENDENCY_OBSERVED, source, target, latency, status, Instant.now());
    }

    private Event removedEvent(String id, String source, String target) {
        return new Event(id, EventType.DEPENDENCY_REMOVED, source, target, null, null, Instant.now());
    }

    private Event metadataEvent(String id, String source) {
        return new Event(id, EventType.SERVICE_METADATA, source, null, null, null, Instant.now());
    }

    private Event heartbeatEvent(String id, String source) {
        return new Event(id, EventType.HEARTBEAT, source, null, null, null, Instant.now());
    }

    // ==================================================================
    // Dispatch
    // ==================================================================

    @Nested
    class DispatchTests {

        @Test
        void dependencyObserved_edgeCreatedInStore() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));

            assertThat(store.getEdge("A", "B")).isPresent();
        }

        @Test
        void dependencyRemoved_edgeRemovedFromStore() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(removedEvent("evt-2", "A", "B"));

            assertThat(store.getEdge("A", "B")).isEmpty();
        }

        @Test
        void serviceMetadata_registersSourceService() {
            manager.processEvent(metadataEvent("evt-1", "svc-a"));

            assertThat(store.containsService("svc-a")).isTrue();
        }

        @Test
        void heartbeat_causesNoStoreMutation() {
            manager.processEvent(heartbeatEvent("evt-1", "svc-a"));

            assertThat(store.edgeCount()).isEqualTo(0);
            assertThat(store.serviceCount()).isEqualTo(0);
        }
    }

    // ==================================================================
    // Duplicate detection
    // ==================================================================

    @Nested
    class DuplicateDetectionTests {

        @Test
        void duplicateEventId_bothCallsProcessed() {
            // GraphStateManager no longer deduplicates — that is EventProcessor's responsibility.
            // Both calls with the same eventId are applied to the graph.
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(observed("evt-1", "A", "B", 200L, EventStatus.OK));

            assertThat(store.getEdge("A", "B").orElseThrow().getRequestCount()).isEqualTo(2L);
        }

        @Test
        void differentEventIds_bothProcessed() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(observed("evt-2", "A", "B", 200L, EventStatus.OK));

            assertThat(store.getEdge("A", "B").orElseThrow().getRequestCount()).isEqualTo(2L);
        }

        @Test
        void nullEventId_bypassesDeduplication_processedEveryTime() {
            // Events with no eventId are always applied — no dedup key to check
            manager.processEvent(observed(null, "A", "B", 100L, EventStatus.OK));
            manager.processEvent(observed(null, "A", "B", 100L, EventStatus.OK));
            manager.processEvent(observed(null, "A", "B", 100L, EventStatus.OK));

            assertThat(store.getEdge("A", "B").orElseThrow().getRequestCount()).isEqualTo(3L);
        }

        @Test
        void duplicateRemoveEventId_secondDropped_noException() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(removedEvent("evt-2", "A", "B"));
            manager.processEvent(removedEvent("evt-2", "A", "B")); // duplicate — must be dropped

            assertThat(store.getEdge("A", "B")).isEmpty();
        }
    }

    // ==================================================================
    // DEPENDENCY_OBSERVED — stat computation
    // ==================================================================

    @Nested
    class DependencyObservedTests {

        @Test
        void firstObservation_correctInitialFields() {
            manager.processEvent(observed("evt-1", "A", "B", 150L, EventStatus.OK));

            Edge edge = store.getEdge("A", "B").orElseThrow();
            assertThat(edge.getAvgLatencyMs()).isEqualTo(150.0);
            assertThat(edge.getRequestCount()).isEqualTo(1L);
            assertThat(edge.getErrorCount()).isEqualTo(0L);
        }

        @Test
        void firstObservation_nullLatency_storedAsZero() {
            manager.processEvent(observed("evt-1", "A", "B", null, EventStatus.OK));

            assertThat(store.getEdge("A", "B").orElseThrow().getAvgLatencyMs()).isEqualTo(0.0);
        }

        @Test
        void firstObservation_nullTimestamp_lastSeenFallsBackToNow() {
            Event event = new Event("evt-1", EventType.DEPENDENCY_OBSERVED, "A", "B", 100L, EventStatus.OK, null);
            manager.processEvent(event);

            assertThat(store.getEdge("A", "B").orElseThrow().getLastSeen()).isNotNull();
        }

        @Test
        void firstObservation_explicitTimestamp_lastSeenMatchesEvent() {
            Instant ts = Instant.parse("2024-06-01T10:00:00Z");
            Event event = new Event("evt-1", EventType.DEPENDENCY_OBSERVED, "A", "B", 100L, EventStatus.OK, ts);
            manager.processEvent(event);

            assertThat(store.getEdge("A", "B").orElseThrow().getLastSeen()).isEqualTo(ts);
        }

        @Test
        void secondObservation_runningAverageComputed() {
            // obs1=100ms, obs2=200ms → avg = (100 + 200) / 2 = 150
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(observed("evt-2", "A", "B", 200L, EventStatus.OK));

            Edge edge = store.getEdge("A", "B").orElseThrow();
            assertThat(edge.getAvgLatencyMs()).isEqualTo(150.0);
            assertThat(edge.getRequestCount()).isEqualTo(2L);
        }

        @Test
        void fiveObservations_runningAverageMatchesSimpleAverage() {
            long[] latencies = {100L, 200L, 300L, 400L, 500L};
            for (int i = 0; i < latencies.length; i++) {
                manager.processEvent(observed("evt-" + i, "A", "B", latencies[i], EventStatus.OK));
            }

            Edge edge = store.getEdge("A", "B").orElseThrow();
            assertThat(edge.getAvgLatencyMs()).isEqualTo(300.0);
            assertThat(edge.getRequestCount()).isEqualTo(5L);
        }

        @Test
        void updateObservation_nullTimestamp_lastSeenFallsBackToNow() {
            Instant original = Instant.parse("2024-01-01T00:00:00Z");
            manager.processEvent(new Event("evt-1", EventType.DEPENDENCY_OBSERVED, "A", "B", 100L, EventStatus.OK, original));
            manager.processEvent(new Event("evt-2", EventType.DEPENDENCY_OBSERVED, "A", "B", 200L, EventStatus.OK, null));

            assertThat(store.getEdge("A", "B").orElseThrow().getLastSeen()).isNotNull();
        }

        @Test
        void errorStatus_incrementsErrorCount() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.ERROR));

            assertThat(store.getEdge("A", "B").orElseThrow().getErrorCount()).isEqualTo(1L);
        }

        @Test
        void okStatus_doesNotIncrementErrorCount() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));

            assertThat(store.getEdge("A", "B").orElseThrow().getErrorCount()).isEqualTo(0L);
        }

        @Test
        void timeoutStatus_doesNotIncrementErrorCount() {
            // TIMEOUT is tracked at the telemetry layer — graph-level errorCount counts ERROR only
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.TIMEOUT));

            assertThat(store.getEdge("A", "B").orElseThrow().getErrorCount()).isEqualTo(0L);
        }

        @Test
        void mixedStatuses_onlyErrorEventsCountedAsErrors() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(observed("evt-2", "A", "B", 100L, EventStatus.ERROR));
            manager.processEvent(observed("evt-3", "A", "B", 100L, EventStatus.TIMEOUT));
            manager.processEvent(observed("evt-4", "A", "B", 100L, EventStatus.ERROR));

            Edge edge = store.getEdge("A", "B").orElseThrow();
            assertThat(edge.getRequestCount()).isEqualTo(4L);
            assertThat(edge.getErrorCount()).isEqualTo(2L);
        }

        @Test
        void nullSource_eventSkipped_noEdgeCreated() {
            manager.processEvent(observed("evt-1", null, "B", 100L, EventStatus.OK));

            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void nullTarget_eventSkipped_noEdgeCreated() {
            manager.processEvent(observed("evt-1", "A", null, 100L, EventStatus.OK));

            assertThat(store.edgeCount()).isEqualTo(0);
        }
    }

    // ==================================================================
    // DEPENDENCY_REMOVED
    // ==================================================================

    @Nested
    class DependencyRemovedTests {

        @Test
        void existingEdge_removedFromStore() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(removedEvent("evt-2", "A", "B"));

            assertThat(store.getEdge("A", "B")).isEmpty();
            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void nonExistentEdge_noExceptionThrown() {
            assertThatCode(() -> manager.processEvent(removedEvent("evt-1", "X", "Y")))
                    .doesNotThrowAnyException();
        }

        @Test
        void removeOneEdge_doesNotAffectSiblingEdgesFromSameSource() {
            manager.processEvent(observed("evt-1", "A", "B", 100L, EventStatus.OK));
            manager.processEvent(observed("evt-2", "A", "C", 100L, EventStatus.OK));
            manager.processEvent(removedEvent("evt-3", "A", "B"));

            assertThat(store.getEdge("A", "B")).isEmpty();
            assertThat(store.getEdge("A", "C")).isPresent();
        }

        @Test
        void nullSource_eventSkipped_noException() {
            assertThatCode(() -> manager.processEvent(removedEvent("evt-1", null, "B")))
                    .doesNotThrowAnyException();
        }

        @Test
        void nullTarget_eventSkipped_noException() {
            assertThatCode(() -> manager.processEvent(removedEvent("evt-1", "A", null)))
                    .doesNotThrowAnyException();
        }
    }

    // ==================================================================
    // SERVICE_METADATA
    // ==================================================================

    @Nested
    class ServiceMetadataTests {

        @Test
        void metadataEvent_registersSourceService() {
            manager.processEvent(metadataEvent("evt-1", "svc-a"));

            assertThat(store.containsService("svc-a")).isTrue();
        }

        @Test
        void metadataEvent_storesNullTeamTierRegion() {
            // Event carries only the service name — full fields require registerService()
            manager.processEvent(metadataEvent("evt-1", "svc-a"));

            ServiceMetadata m = store.getMetadata("svc-a").orElseThrow();
            assertThat(m.getTeam()).isNull();
            assertThat(m.getTier()).isNull();
            assertThat(m.getRegion()).isNull();
        }

        @Test
        void metadataEvent_nullSource_skipped_noException() {
            assertThatCode(() -> manager.processEvent(metadataEvent("evt-1", null)))
                    .doesNotThrowAnyException();
            assertThat(store.serviceCount()).isEqualTo(0);
        }

        @Test
        void registerService_storesFullMetadata() {
            manager.registerService(new ServiceMetadata("svc-a", "payments", "tier-1", "us-east-1"));

            ServiceMetadata stored = store.getMetadata("svc-a").orElseThrow();
            assertThat(stored.getTeam()).isEqualTo("payments");
            assertThat(stored.getTier()).isEqualTo("tier-1");
            assertThat(stored.getRegion()).isEqualTo("us-east-1");
        }

        @Test
        void registerService_updatesExistingEntry() {
            manager.registerService(new ServiceMetadata("svc-a", "old-team", "tier-1", "us-east-1"));
            manager.registerService(new ServiceMetadata("svc-a", "new-team", "tier-2", "eu-west-1"));

            assertThat(store.getMetadata("svc-a").orElseThrow().getTeam()).isEqualTo("new-team");
            assertThat(store.serviceCount()).isEqualTo(1);
        }
    }

    // ==================================================================
    // removeService (public API)
    // ==================================================================

    @Nested
    class RemoveServiceTests {

        @Test
        void removeService_metadataDeleted() {
            manager.registerService(new ServiceMetadata("svc-a", "team", "tier-1", "us-east-1"));
            manager.removeService("svc-a");

            assertThat(store.containsService("svc-a")).isFalse();
        }

        @Test
        void removeService_allOutgoingEdgesDeleted() {
            manager.processEvent(observed("evt-1", "svc-a", "svc-b", 100L, EventStatus.OK));
            manager.processEvent(observed("evt-2", "svc-a", "svc-c", 100L, EventStatus.OK));
            manager.removeService("svc-a");

            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void removeService_allIncomingEdgesDeleted() {
            manager.processEvent(observed("evt-1", "svc-x", "svc-a", 100L, EventStatus.OK));
            manager.processEvent(observed("evt-2", "svc-y", "svc-a", 100L, EventStatus.OK));
            manager.removeService("svc-a");

            assertThat(store.edgeCount()).isEqualTo(0);
            assertThat(store.getIncomingEdges("svc-a")).isEmpty();
        }

        @Test
        void removeUnknownService_noException() {
            assertThatCode(() -> manager.removeService("does-not-exist"))
                    .doesNotThrowAnyException();
        }
    }

    // ==================================================================
    // Concurrent access
    // ==================================================================

    @Nested
    class ConcurrentAccessTests {

        /**
         * Primary regression for the per-edge lock on OBSERVED:
         * if any two threads could race on the read-compute-write cycle, some
         * increments would be lost and requestCount would be < threads.
         */
        @Test
        void concurrentObservedSameEdge_noLostIncrements() throws InterruptedException {
            int threads = 50;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final String id = "evt-" + i;
                exec.submit(() -> {
                    try {
                        start.await();
                        manager.processEvent(observed(id, "A", "B", 100L, EventStatus.OK));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.getEdge("A", "B").orElseThrow().getRequestCount()).isEqualTo(threads);
        }

        @Test
        void concurrentObservedSameEdge_correctRunningAverage() throws InterruptedException {
            // All observations use the same latency — avg must equal that value regardless of ordering
            int threads = 40;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final String id = "evt-" + i;
                exec.submit(() -> {
                    try {
                        start.await();
                        manager.processEvent(observed(id, "A", "B", 200L, EventStatus.OK));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            Edge edge = store.getEdge("A", "B").orElseThrow();
            assertThat(edge.getRequestCount()).isEqualTo(threads);
            assertThat(edge.getAvgLatencyMs()).isEqualTo(200.0);
        }

        @Test
        void concurrentObservedSameEdge_correctErrorCount() throws InterruptedException {
            int threads     = 40;
            int errorCount  = 20; // exactly half
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final String id     = "evt-" + i;
                final EventStatus s = (i < errorCount) ? EventStatus.ERROR : EventStatus.OK;
                exec.submit(() -> {
                    try {
                        start.await();
                        manager.processEvent(observed(id, "A", "B", 100L, s));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            Edge edge = store.getEdge("A", "B").orElseThrow();
            assertThat(edge.getRequestCount()).isEqualTo(threads);
            assertThat(edge.getErrorCount()).isEqualTo(errorCount);
        }

        @Test
        void concurrentObservedDistinctEdges_allEdgesCreated() throws InterruptedException {
            int threads = 20;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        manager.processEvent(observed("evt-" + idx, "src-" + idx, "tgt-" + idx, 100L, EventStatus.OK));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.edgeCount()).isEqualTo(threads);
            for (int i = 0; i < threads; i++) {
                assertThat(store.getEdge("src-" + i, "tgt-" + i)).isPresent();
            }
        }

        @Test
        void concurrentDuplicateEventIds_allProcessed() throws InterruptedException {
            // GraphStateManager no longer deduplicates — all N threads should each
            // increment the request count, giving a total equal to the thread count.
            int threads = 30;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        manager.processEvent(observed("SHARED-ID", "A", "B", 100L, EventStatus.OK));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.getEdge("A", "B").orElseThrow().getRequestCount()).isEqualTo((long) threads);
        }

        /**
         * Regression test for the OBSERVED/REMOVED race:
         * without the per-edge lock on REMOVED, a REMOVED arriving between an OBSERVED's
         * getEdge and putEdge would be silently overwritten, leaving the edge alive.
         * Verified by seeding an edge and racing many REMOVED calls — the edge must
         * be absent once all REMOVED threads have completed.
         */
        @Test
        void concurrentRemovedSameEdge_edgeAbsentAfterAllComplete() throws InterruptedException {
            manager.processEvent(observed("seed", "A", "B", 100L, EventStatus.OK));

            int threads = 10;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final String id = "rem-" + i; // distinct IDs bypass dedup
                exec.submit(() -> {
                    try {
                        start.await();
                        manager.processEvent(removedEvent(id, "A", "B"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.getEdge("A", "B")).isEmpty();
            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void concurrentMixedEventTypes_noExceptions() throws InterruptedException {
            int threads = 40;
            CountDownLatch start      = new CountDownLatch(1);
            CountDownLatch done       = new CountDownLatch(threads);
            ExecutorService exec      = Executors.newFixedThreadPool(threads);
            AtomicInteger exceptions  = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        switch (idx % 4) {
                            case 0 -> manager.processEvent(observed("o" + idx, "A", "B", 100L, EventStatus.OK));
                            case 1 -> manager.processEvent(removedEvent("r" + idx, "A", "B"));
                            case 2 -> manager.processEvent(metadataEvent("m" + idx, "svc-a"));
                            case 3 -> manager.processEvent(heartbeatEvent("h" + idx, "svc-a"));
                        }
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(exceptions.get()).isEqualTo(0);
        }
    }
}
