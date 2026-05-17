package com.defendermate.graph.graph;

import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.ServiceMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GraphStoreTest {

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Edge edge(String source, String target) {
        return new Edge(source, target, 10.0, 1L, 0L, Instant.now());
    }

    private Edge edge(String source, String target, double avgLatency, long requests, long errors) {
        return new Edge(source, target, avgLatency, requests, errors, Instant.now());
    }

    private ServiceMetadata metadata(String service) {
        return new ServiceMetadata(service, "team-a", "tier-1", "us-east-1");
    }

    // ==================================================================
    // PutEdge
    // ==================================================================

    @Nested
    class PutEdgeTests {

        @Test
        void newEdge_storedInOutgoingMap() {
            store.putEdge(edge("A", "B"));

            assertThat(store.getEdge("A", "B")).isPresent();
        }

        @Test
        void newEdge_mirroredInIncomingMap() {
            store.putEdge(edge("A", "B"));

            assertThat(store.getIncomingEdges("B")).hasSize(1);
            assertThat(store.getIncomingNeighbors("B")).containsExactly("A");
        }

        @Test
        void newEdge_fieldsStoredCorrectly() {
            store.putEdge(edge("A", "B", 25.0, 5L, 2L));

            Edge stored = store.getEdge("A", "B").orElseThrow();
            assertThat(stored.getAvgLatencyMs()).isEqualTo(25.0);
            assertThat(stored.getRequestCount()).isEqualTo(5L);
            assertThat(stored.getErrorCount()).isEqualTo(2L);
        }

        @Test
        void existingEdge_replacedWithNewEdge() {
            store.putEdge(edge("A", "B", 10.0, 1L, 0L));

            store.putEdge(edge("A", "B", 50.0, 10L, 3L));
            Edge stored = store.getEdge("A", "B").orElseThrow();

            assertThat(stored.getAvgLatencyMs()).isEqualTo(50.0);
            assertThat(stored.getRequestCount()).isEqualTo(10L);
            assertThat(stored.getErrorCount()).isEqualTo(3L);
        }

        @Test
        void existingEdge_replacedEdge_incomingMapReflectsNewValues() {
            store.putEdge(edge("A", "B", 10.0, 1L, 0L));
            store.putEdge(edge("A", "B", 50.0, 10L, 3L));

            Edge fromIncoming = store.getIncomingEdges("B").iterator().next();
            assertThat(fromIncoming.getAvgLatencyMs()).isEqualTo(50.0);
        }

        @Test
        void multipleTargets_storedUnderSameSourceEntry() {
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("A", "C"));

            assertThat(store.getOutgoingEdges("A")).hasSize(2);
            assertThat(store.getOutgoingNeighbors("A")).containsExactlyInAnyOrder("B", "C");
        }

        @Test
        void multipleSourcesPointingToSameTarget() {
            store.putEdge(edge("A", "C"));
            store.putEdge(edge("B", "C"));

            assertThat(store.getIncomingEdges("C")).hasSize(2);
            assertThat(store.getIncomingNeighbors("C")).containsExactlyInAnyOrder("A", "B");
        }
    }

    // ==================================================================
    // RemoveEdge
    // ==================================================================

    @Nested
    class RemoveEdgeTests {

        @Test
        void existingEdge_removedFromOutgoingMap() {
            store.putEdge(edge("A", "B"));
            store.removeEdge("A", "B");

            assertThat(store.getEdge("A", "B")).isEmpty();
        }

        @Test
        void existingEdge_removedFromIncomingMap() {
            store.putEdge(edge("A", "B"));
            store.removeEdge("A", "B");

            assertThat(store.getIncomingEdges("B")).isEmpty();
        }

        @Test
        void existingEdge_returnsTrue() {
            store.putEdge(edge("A", "B"));

            assertThat(store.removeEdge("A", "B")).isTrue();
        }

        @Test
        void nonExistentEdge_returnsFalse() {
            assertThat(store.removeEdge("X", "Y")).isFalse();
        }

        @Test
        void unknownSource_returnsFalse() {
            store.putEdge(edge("A", "B"));

            assertThat(store.removeEdge("Z", "B")).isFalse();
        }

        @Test
        void remove_doesNotAffectOtherEdgesFromSameSource() {
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("A", "C"));
            store.removeEdge("A", "B");

            assertThat(store.getEdge("A", "C")).isPresent();
            assertThat(store.getOutgoingEdges("A")).hasSize(1);
        }

        @Test
        void remove_doesNotAffectOtherEdgesToSameTarget() {
            store.putEdge(edge("A", "C"));
            store.putEdge(edge("B", "C"));
            store.removeEdge("A", "C");

            assertThat(store.getIncomingEdges("C")).hasSize(1);
            assertThat(store.getIncomingNeighbors("C")).containsExactly("B");
        }

        @Test
        void removeLastEdgeFromSource_prunesOuterEntry() {
            store.putEdge(edge("A", "B"));
            store.removeEdge("A", "B");

            assertThat(store.getOutgoingEdges("A")).isEmpty();
            assertThat(store.getOutgoingNeighbors("A")).isEmpty();
        }

        @Test
        void removeLastEdgeToTarget_prunesIncomingEntry() {
            store.putEdge(edge("A", "B"));
            store.removeEdge("A", "B");

            assertThat(store.getIncomingEdges("B")).isEmpty();
            assertThat(store.getIncomingNeighbors("B")).isEmpty();
        }
    }

    // ==================================================================
    // Metadata
    // ==================================================================

    @Nested
    class MetadataTests {

        @Test
        void putMetadata_canBeRetrieved() {
            store.putMetadata(metadata("svc-a"));

            assertThat(store.getMetadata("svc-a")).isPresent();
        }

        @Test
        void putMetadata_fieldsCorrect() {
            store.putMetadata(new ServiceMetadata("svc-a", "payments", "tier-2", "eu-west-1"));

            ServiceMetadata m = store.getMetadata("svc-a").orElseThrow();
            assertThat(m.getTeam()).isEqualTo("payments");
            assertThat(m.getTier()).isEqualTo("tier-2");
            assertThat(m.getRegion()).isEqualTo("eu-west-1");
        }

        @Test
        void putMetadata_updatesExistingEntry() {
            store.putMetadata(new ServiceMetadata("svc-a", "old-team", "tier-1", "us-east-1"));
            store.putMetadata(new ServiceMetadata("svc-a", "new-team", "tier-2", "eu-west-1"));

            assertThat(store.getMetadata("svc-a").orElseThrow().getTeam()).isEqualTo("new-team");
            assertThat(store.serviceCount()).isEqualTo(1);
        }

        @Test
        void unknownService_returnsEmpty() {
            assertThat(store.getMetadata("unknown")).isEmpty();
        }

        @Test
        void containsService_trueAfterPut() {
            store.putMetadata(metadata("svc-a"));

            assertThat(store.containsService("svc-a")).isTrue();
        }

        @Test
        void containsService_falseForUnknown() {
            assertThat(store.containsService("nobody")).isFalse();
        }

        @Test
        void containsService_falseForServiceOnlyInEdge() {
            // putEdge does NOT register services in the metadata index
            store.putEdge(edge("svc-a", "svc-b"));

            assertThat(store.containsService("svc-a")).isFalse();
            assertThat(store.containsService("svc-b")).isFalse();
        }
    }

    // ==================================================================
    // RemoveService
    // ==================================================================

    @Nested
    class RemoveServiceTests {

        @Test
        void removeService_metadataDeleted() {
            store.putMetadata(metadata("svc-a"));
            store.removeService("svc-a");

            assertThat(store.containsService("svc-a")).isFalse();
        }

        @Test
        void removeService_allOutgoingEdgesDeleted() {
            store.putEdge(edge("svc-a", "svc-b"));
            store.putEdge(edge("svc-a", "svc-c"));
            store.removeService("svc-a");

            assertThat(store.getOutgoingEdges("svc-a")).isEmpty();
            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void removeService_allIncomingEdgesDeleted() {
            store.putEdge(edge("svc-x", "svc-a"));
            store.putEdge(edge("svc-y", "svc-a"));
            store.removeService("svc-a");

            assertThat(store.getIncomingEdges("svc-a")).isEmpty();
            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void removeService_reverseLinksCleanedFromTargets() {
            store.putEdge(edge("svc-a", "svc-b"));
            store.removeService("svc-a");

            assertThat(store.getIncomingNeighbors("svc-b")).doesNotContain("svc-a");
        }

        @Test
        void removeService_reverseLinksCleanedFromSources() {
            store.putEdge(edge("svc-x", "svc-a"));
            store.removeService("svc-a");

            assertThat(store.getOutgoingNeighbors("svc-x")).doesNotContain("svc-a");
        }

        @Test
        void removeService_unrelatedEdgesUntouched() {
            store.putEdge(edge("svc-x", "svc-y"));
            store.putEdge(edge("svc-a", "svc-b"));
            store.removeService("svc-a");

            assertThat(store.getEdge("svc-x", "svc-y")).isPresent();
            assertThat(store.edgeCount()).isEqualTo(1);
        }

        @Test
        void removeUnknownService_isNoOp() {
            store.putEdge(edge("A", "B"));
            store.removeService("does-not-exist");

            assertThat(store.edgeCount()).isEqualTo(1);
        }
    }

    // ==================================================================
    // Queries
    // ==================================================================

    @Nested
    class QueryTests {

        @Test
        void getEdge_unknownSource_returnsEmpty() {
            assertThat(store.getEdge("X", "Y")).isEmpty();
        }

        @Test
        void getEdge_unknownTarget_returnsEmpty() {
            store.putEdge(edge("A", "B"));

            assertThat(store.getEdge("A", "Z")).isEmpty();
        }

        @Test
        void getOutgoingEdges_unknownService_returnsEmptySet() {
            assertThat(store.getOutgoingEdges("nobody")).isEmpty();
        }

        @Test
        void getIncomingEdges_unknownService_returnsEmptySet() {
            assertThat(store.getIncomingEdges("nobody")).isEmpty();
        }

        @Test
        void getOutgoingNeighbors_unknownService_returnsEmptySet() {
            assertThat(store.getOutgoingNeighbors("nobody")).isEmpty();
        }

        @Test
        void getIncomingNeighbors_unknownService_returnsEmptySet() {
            assertThat(store.getIncomingNeighbors("nobody")).isEmpty();
        }

        @Test
        void getOutgoingNeighbors_returnsCorrectTargetNames() {
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("A", "C"));

            assertThat(store.getOutgoingNeighbors("A")).containsExactlyInAnyOrder("B", "C");
        }

        @Test
        void getIncomingNeighbors_returnsCorrectSourceNames() {
            store.putEdge(edge("B", "A"));
            store.putEdge(edge("C", "A"));

            assertThat(store.getIncomingNeighbors("A")).containsExactlyInAnyOrder("B", "C");
        }

        @Test
        void getAllServices_emptyWhenNoneRegistered() {
            assertThat(store.getAllServices()).isEmpty();
        }

        @Test
        void getAllServices_returnsAllRegistered() {
            store.putMetadata(metadata("svc-a"));
            store.putMetadata(metadata("svc-b"));

            assertThat(store.getAllServices()).containsExactlyInAnyOrder("svc-a", "svc-b");
        }

        @Test
        void getAllServices_doesNotIncludeServicesOnlySeenInEdges() {
            // Services that appear only as edge endpoints are not in the metadata index
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("B", "C"));

            assertThat(store.getAllServices()).isEmpty();
        }

        @Test
        void returnedEdgeSets_areImmutableSnapshots() {
            store.putEdge(edge("A", "B"));
            Set<Edge> snapshot = store.getOutgoingEdges("A");

            store.putEdge(edge("A", "C")); // mutate store after snapshot

            assertThat(snapshot).hasSize(1); // snapshot unchanged
        }

        @Test
        void returnedServiceSet_isImmutableSnapshot() {
            store.putMetadata(metadata("svc-a"));
            Set<String> snapshot = store.getAllServices();

            store.putMetadata(metadata("svc-b"));

            assertThat(snapshot).containsExactly("svc-a");
        }
    }

    // ==================================================================
    // Counts
    // ==================================================================

    @Nested
    class CountTests {

        @Test
        void edgeCount_zeroInitially() {
            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void edgeCount_incrementsOnDistinctPuts() {
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("A", "C"));
            store.putEdge(edge("D", "E"));

            assertThat(store.edgeCount()).isEqualTo(3);
        }

        @Test
        void edgeCount_notDoubledByUpdate() {
            store.putEdge(edge("A", "B", 10.0, 1L, 0L));
            store.putEdge(edge("A", "B", 20.0, 2L, 1L));

            assertThat(store.edgeCount()).isEqualTo(1);
        }

        @Test
        void edgeCount_decrementsOnRemove() {
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("A", "C"));
            store.removeEdge("A", "B");

            assertThat(store.edgeCount()).isEqualTo(1);
        }

        @Test
        void edgeCount_zeroAfterRemoveAll() {
            store.putEdge(edge("A", "B"));
            store.removeEdge("A", "B");

            assertThat(store.edgeCount()).isEqualTo(0);
        }

        @Test
        void serviceCount_zeroInitially() {
            assertThat(store.serviceCount()).isEqualTo(0);
        }

        @Test
        void serviceCount_incrementsOnDistinctPuts() {
            store.putMetadata(metadata("svc-a"));
            store.putMetadata(metadata("svc-b"));

            assertThat(store.serviceCount()).isEqualTo(2);
        }

        @Test
        void serviceCount_notDoubledByUpdate() {
            store.putMetadata(metadata("svc-a"));
            store.putMetadata(new ServiceMetadata("svc-a", "new-team", "tier-2", "eu-west-1"));

            assertThat(store.serviceCount()).isEqualTo(1);
        }

        @Test
        void serviceCount_doesNotCountServicesOnlyInEdges() {
            // Edge endpoints are not counted — only putMetadata registrations are
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("B", "C"));
            store.putEdge(edge("A", "C"));

            assertThat(store.serviceCount()).isEqualTo(0);
        }

        @Test
        void serviceCount_decrementsOnRemove() {
            store.putMetadata(metadata("svc-a"));
            store.putMetadata(metadata("svc-b"));
            store.removeService("svc-a");

            assertThat(store.serviceCount()).isEqualTo(1);
        }
    }

    // ==================================================================
    // Concurrent access
    // ==================================================================

    @Nested
    class ConcurrentAccessTests {

        @Test
        void concurrentPutEdge_allDistinctEdgesStored() throws InterruptedException {
            int threads = 10;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        store.putEdge(edge("src-" + idx, "tgt-" + idx));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.edgeCount()).isEqualTo(threads);
        }

        @Test
        void concurrentUpdatesToSameEdge_exactlyOneEdgeStored() throws InterruptedException {
            store.putEdge(edge("A", "B", 0.0, 0L, 0L));

            int threads = 20;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 1; i <= threads; i++) {
                final long count = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        store.putEdge(edge("A", "B", count * 10.0, count, 0L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.edgeCount()).isEqualTo(1);
            assertThat(store.getEdge("A", "B")).isPresent();
            // Incoming index must also show exactly one entry — concurrent plain-replaces
            // must not create duplicate keys in the inner ConcurrentHashMap
            assertThat(store.getIncomingEdges("B")).hasSize(1);
            assertThat(store.getIncomingNeighbors("B")).containsExactly("A");
        }

        @Test
        void concurrentReads_neverThrow() throws InterruptedException {
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("A", "C"));
            store.putEdge(edge("B", "C"));

            int threads = 20;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        store.getOutgoingEdges("A");
                        store.getIncomingEdges("C");
                        store.getOutgoingNeighbors("A");
                        store.getIncomingNeighbors("C");
                        store.edgeCount();
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(errors.get()).isEqualTo(0);
        }

        @Test
        void interleavedPutAndRemove_edgeCountInValidRange() throws InterruptedException {
            int pairs = 10;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(pairs * 2);
            ExecutorService exec = Executors.newFixedThreadPool(pairs * 2);

            for (int i = 0; i < pairs; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try { start.await(); store.putEdge(edge("p-" + idx, "q-" + idx)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
                exec.submit(() -> {
                    try { start.await(); store.removeEdge("p-" + idx, "q-" + idx); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.edgeCount()).isBetween(0, pairs);
        }

        @Test
        void concurrentRemoveService_doesNotLeakEdges() throws InterruptedException {
            store.putEdge(edge("A", "B"));
            store.putEdge(edge("C", "A"));
            store.putEdge(edge("A", "C"));

            int threads = 5;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                exec.submit(() -> {
                    try { start.await(); store.removeService("A"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { done.countDown(); }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.getOutgoingEdges("A")).isEmpty();
            assertThat(store.getIncomingEdges("A")).isEmpty();
            assertThat(store.getIncomingNeighbors("B")).doesNotContain("A");
            assertThat(store.getOutgoingNeighbors("C")).doesNotContain("A");
        }

        @Test
        void concurrentPutsToDistinctServices_storeIsolated() throws InterruptedException {
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService exec = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        // each thread writes 3 edges for its own source service
                        store.putEdge(edge("svc-" + idx, "dep-1"));
                        store.putEdge(edge("svc-" + idx, "dep-2"));
                        store.putEdge(edge("svc-" + idx, "dep-3"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            exec.shutdown();

            assertThat(store.edgeCount()).isEqualTo(threads * 3);
            for (int i = 0; i < threads; i++) {
                assertThat(store.getOutgoingNeighbors("svc-" + i))
                        .containsExactlyInAnyOrder("dep-1", "dep-2", "dep-3");
            }
        }
    }
}
