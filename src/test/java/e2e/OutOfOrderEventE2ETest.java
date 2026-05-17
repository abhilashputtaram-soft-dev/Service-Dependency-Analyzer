package e2e;

import com.defendermate.graph.graph.GraphStore;
import com.defendermate.graph.ingestion.EventRequest;
import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for out-of-order and concurrent event ingestion.
 *
 * <p>All events are published through the real HTTP ingestion API
 * ({@code POST /api/ingestion/event}).  No mocks are used.  The tests
 * verify that the system:
 * <ol>
 *   <li>Does <em>not crash</em> when events arrive in unexpected order.</li>
 *   <li>Keeps the {@link GraphStore} in a consistent state after every sequence.</li>
 *   <li>Continues processing new events normally after handling anomalous input.</li>
 *   <li>Handles concurrent producers without deadlocks or lost updates.</li>
 * </ol>
 *
 * <h3>Key design invariants exercised</h3>
 * <ul>
 *   <li>{@code DEPENDENCY_REMOVED} on a non-existent edge is a no-op (logs a WARN,
 *       returns {@code false} from {@code GraphStore.removeEdge}, does not throw).</li>
 *   <li>{@code DEPENDENCY_OBSERVED} after a {@code DEPENDENCY_REMOVED} always creates
 *       a <em>fresh</em> edge — accumulated stats from a prior edge are discarded.</li>
 *   <li>Per-edge {@link java.util.concurrent.locks.ReentrantLock} in
 *       {@code GraphStateManager} serialises concurrent OBSERVED / REMOVED mutations
 *       on the same {@code (source, target)} pair, preventing silent overwrites.</li>
 * </ul>
 *
 * <h3>Synchronisation strategy</h3>
 * <p>The ingestion queue is a FIFO {@link java.util.concurrent.LinkedBlockingQueue}.
 * A "sentinel" {@code DEPENDENCY_OBSERVED} for a dedicated edge pair is published
 * after any sequence of events under test.  Once the sentinel edge appears in
 * {@code GraphStore} the test knows all prior events in the queue have also been
 * fully processed — eliminating race conditions caused by relying on queue-size alone.
 *
 * <p>Tag: {@code e2e} — run individually with {@code mvn test -Dgroups=e2e}.
 */
@Tag("e2e")
@DisplayName("Out-of-Order Event Handling")
class OutOfOrderEventE2ETest extends BaseE2ETest {

    @Autowired
    private GraphStore graphStore;

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Publishes a {@code DEPENDENCY_OBSERVED} event with no latency metadata. */
    private void publishObserve(String source, String target) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.DEPENDENCY_OBSERVED, source, target, null, null, null));
    }

    /** Publishes a {@code DEPENDENCY_OBSERVED} event with an explicit latency. */
    private void publishObserveWithLatency(String source, String target, long latencyMs) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.DEPENDENCY_OBSERVED, source, target, latencyMs, null, null));
    }

    /** Publishes a {@code DEPENDENCY_REMOVED} event. */
    private void publishRemove(String source, String target) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.DEPENDENCY_REMOVED, source, target, null, null, null));
    }

    /**
     * Blocks until {@code graphStore.edgeCount()} equals {@code expected}.
     * Fails after 5 seconds.
     */
    private void awaitEdgeCount(int expected) {
        awaitAsserted(
                () -> assertThat(graphStore.edgeCount()).isEqualTo(expected),
                Duration.ofSeconds(5));
    }

    /**
     * Blocks until the edge {@code source → target} is present in the graph.
     * Fails after 5 seconds.
     */
    private void awaitEdgePresent(String source, String target) {
        awaitAsserted(
                () -> assertThat(graphStore.getEdge(source, target)).isPresent(),
                Duration.ofSeconds(5));
    }

    /**
     * Blocks until the edge {@code source → target} is absent from the graph.
     * Fails after 5 seconds.
     */
    private void awaitEdgeAbsent(String source, String target) {
        awaitAsserted(
                () -> assertThat(graphStore.getEdge(source, target)).isEmpty(),
                Duration.ofSeconds(5));
    }

    // =========================================================================
    // Remove Before Add
    // =========================================================================

    @Nested
    @DisplayName("Remove Before Add")
    class RemoveBeforeAddTests {

        /**
         * A {@code DEPENDENCY_REMOVED} for an edge that was never observed must be a
         * silent no-op.  The pipeline must not crash, must return 202 Accepted, and
         * must continue processing subsequent events normally.
         *
         * <p>The sentinel edge for a distinct pair confirms the spurious REMOVE was
         * fully processed (FIFO queue guarantees ordering).
         */
        @Test
        @DisplayName("DEPENDENCY_REMOVED on non-existent edge is accepted (202) and edge stays absent")
        void removeOnNonExistentEdgeIsNoopAndEdgeRemainsAbsent() {
            // HTTP layer must accept the REMOVE without a 4xx/5xx even though the edge is absent
            var response = ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_REMOVED, "ghost-src", "ghost-tgt", null, null, null));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            // Sentinel: once this edge appears the REMOVE has been fully processed (FIFO)
            publishObserve("rba-sentinel", "rba-end");
            awaitEdgePresent("rba-sentinel", "rba-end");

            assertThat(graphStore.getEdge("ghost-src", "ghost-tgt")).isEmpty();
            assertThat(graphStore.edgeCount()).isEqualTo(1); // only the sentinel
        }

        /**
         * A spurious {@code DEPENDENCY_REMOVED} followed by a {@code DEPENDENCY_OBSERVED}
         * for the same pair must create a single fresh edge — the prior no-op remove
         * must not corrupt the subsequent add.
         *
         * <pre>
         * REMOVE(rba-src → rba-tgt)     → no-op (edge absent)
         * OBSERVE(rba-src → rba-tgt, 75 ms) → fresh edge: count=1, avg=75 ms
         * </pre>
         */
        @Test
        @DisplayName("OBSERVE after spurious REMOVE creates a fresh edge with requestCount=1")
        void removeBeforeObserveCreatesEdgeWithFreshState() {
            publishRemove("rba-src", "rba-tgt");                 // no-op
            publishObserveWithLatency("rba-src", "rba-tgt", 75); // should create edge

            awaitEdgePresent("rba-src", "rba-tgt");

            Edge edge = graphStore.getEdge("rba-src", "rba-tgt").get();
            assertThat(edge.getRequestCount()).isEqualTo(1L);
            assertThat(edge.getAvgLatencyMs()).isEqualTo(75.0);
            assertThat(graphStore.edgeCount()).isEqualTo(1);
        }

        /**
         * Multiple spurious removes for distinct non-existent pairs must not
         * stall or crash the pipeline; subsequent OBSERVE events must still be
         * processed and create edges normally.
         */
        @Test
        @DisplayName("multiple spurious removes for different non-existent pairs do not block the pipeline")
        void multipleSpuriousRemovesOnDifferentEdgesDoNotBlockPipeline() {
            // Three ghost removes — none of these edges exist
            publishRemove("ghost-A", "ghost-out");
            publishRemove("ghost-B", "ghost-out");
            publishRemove("ghost-C", "ghost-out");

            // Real OBSERVE events follow — pipeline must process all three
            publishObserve("real-A", "real-out");
            publishObserve("real-B", "real-out");
            publishObserve("real-C", "real-out");
            awaitEdgeCount(3);

            assertThat(graphStore.getEdge("ghost-A", "ghost-out")).isEmpty();
            assertThat(graphStore.getEdge("ghost-B", "ghost-out")).isEmpty();
            assertThat(graphStore.getEdge("ghost-C", "ghost-out")).isEmpty();
            assertThat(graphStore.getEdge("real-A",  "real-out")).isPresent();
            assertThat(graphStore.getEdge("real-B",  "real-out")).isPresent();
            assertThat(graphStore.getEdge("real-C",  "real-out")).isPresent();
        }
    }

    // =========================================================================
    // Duplicate Remove Events
    // =========================================================================

    @Nested
    @DisplayName("Duplicate Remove Events")
    class DuplicateRemoveTests {

        /**
         * Two consecutive {@code DEPENDENCY_REMOVED} events for the same existing
         * edge: the first removes it, the second is a no-op.  The pipeline must
         * continue processing normally after both.
         */
        @Test
        @DisplayName("duplicate REMOVE on existing edge: first removes it, second is a no-op")
        void duplicateRemoveOnExistingEdgeRemovesOnceAndSecondIsNoOp() {
            publishObserve("dup-src", "dup-tgt");
            awaitEdgePresent("dup-src", "dup-tgt");

            publishRemove("dup-src", "dup-tgt"); // first REMOVE — removes the edge
            publishRemove("dup-src", "dup-tgt"); // second REMOVE — no-op on absent edge

            // Sentinel proves both removes were fully processed before this assertion
            publishObserve("dup-sentinel", "dup-end");
            awaitEdgePresent("dup-sentinel", "dup-end");

            assertThat(graphStore.getEdge("dup-src", "dup-tgt")).isEmpty();
            assertThat(graphStore.edgeCount()).isEqualTo(1); // only the sentinel
        }

        /**
         * Three consecutive removes for an edge that was never created: all three
         * must be no-ops, leaving the pipeline healthy for future events.
         */
        @Test
        @DisplayName("three duplicate removes on non-existent edge are all idempotent no-ops")
        void tripleRemoveOnNonExistentEdgeIsFullyIdempotent() {
            publishRemove("noop-src", "noop-tgt");
            publishRemove("noop-src", "noop-tgt");
            publishRemove("noop-src", "noop-tgt");

            publishObserve("noop-sentinel", "noop-end");
            awaitEdgePresent("noop-sentinel", "noop-end");

            assertThat(graphStore.getEdge("noop-src", "noop-tgt")).isEmpty();
            assertThat(graphStore.edgeCount()).isEqualTo(1);
        }
    }

    // =========================================================================
    // Observe → Remove → Observe Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Observe-Remove-Observe Lifecycle")
    class ObserveRemoveObserveTests {

        /**
         * After an edge is added and then removed, a late-arriving
         * {@code DEPENDENCY_OBSERVED} must create a brand-new edge.
         * Stats must <em>not</em> be accumulated from the prior edge.
         *
         * <pre>
         * OBSERVE(100 ms) → count=1, avg=100 ms
         * REMOVE          → edge absent
         * OBSERVE(200 ms) → fresh edge: count=1, avg=200 ms  (NOT count=2, avg=150 ms)
         * </pre>
         */
        @Test
        @DisplayName("re-observed edge after removal starts fresh: prior stats are not carried over")
        void reObservedEdgeAfterRemovalHasFreshStats() {
            publishObserveWithLatency("oror-src", "oror-tgt", 100);
            awaitEdgePresent("oror-src", "oror-tgt");

            Edge first = graphStore.getEdge("oror-src", "oror-tgt").get();
            assertThat(first.getRequestCount()).isEqualTo(1L);
            assertThat(first.getAvgLatencyMs()).isEqualTo(100.0);

            publishRemove("oror-src", "oror-tgt");
            awaitEdgeAbsent("oror-src", "oror-tgt");

            // Late-arriving observer: must produce a fresh edge
            publishObserveWithLatency("oror-src", "oror-tgt", 200);
            awaitEdgePresent("oror-src", "oror-tgt");

            Edge fresh = graphStore.getEdge("oror-src", "oror-tgt").get();
            assertThat(fresh.getRequestCount()).isEqualTo(1L);      // NOT 2
            assertThat(fresh.getAvgLatencyMs()).isEqualTo(200.0);   // NOT 150.0
        }

        /**
         * Accumulated stats built up across multiple observations must be fully
         * discarded when the edge is removed.
         *
         * <pre>
         * OBSERVE( 0 ms) → count=1, avg=  0.0 ms
         * OBSERVE(20 ms) → count=2, avg= 10.0 ms
         * OBSERVE(40 ms) → count=3, avg= 20.0 ms
         * REMOVE         → edge absent
         * OBSERVE(60 ms) → fresh edge: count=1, avg=60.0 ms  (history fully reset)
         * </pre>
         */
        @Test
        @DisplayName("accumulated stats (count=3, avg=20ms) are fully reset after remove and re-add")
        void accumulatedStatsAreDiscardedOnRemoveAndReAdd() {
            publishObserveWithLatency("acc-src", "acc-tgt", 0);
            publishObserveWithLatency("acc-src", "acc-tgt", 20);
            publishObserveWithLatency("acc-src", "acc-tgt", 40);

            // Wait until all three observations have been incorporated
            awaitAsserted(() -> {
                Optional<Edge> e = graphStore.getEdge("acc-src", "acc-tgt");
                assertThat(e).isPresent();
                assertThat(e.get().getRequestCount()).isEqualTo(3L);
                assertThat(e.get().getAvgLatencyMs()).isEqualTo(20.0);
            }, Duration.ofSeconds(5));

            publishRemove("acc-src", "acc-tgt");
            awaitEdgeAbsent("acc-src", "acc-tgt");

            publishObserveWithLatency("acc-src", "acc-tgt", 60);
            awaitEdgePresent("acc-src", "acc-tgt");

            Edge reset = graphStore.getEdge("acc-src", "acc-tgt").get();
            assertThat(reset.getRequestCount()).isEqualTo(1L);    // history reset to 1
            assertThat(reset.getAvgLatencyMs()).isEqualTo(60.0);  // only the latest value
        }
    }

    // =========================================================================
    // Interleaved Producer Ordering
    // =========================================================================

    @Nested
    @DisplayName("Interleaved Producer Ordering")
    class InterleavedOrderingTests {

        /**
         * Events for three independent edge pairs are published in a deliberately
         * interleaved order mixing spurious removes, adds, removes, and re-adds.
         * The test verifies that the queue's FIFO ordering produces exactly the
         * predicted final state for every pair.
         *
         * <h4>Event stream (in submission order)</h4>
         * <pre>
         *  1. OBSERVE A      → A: count=1
         *  2. REMOVE  B      → B: spurious (still absent)
         *  3. OBSERVE A      → A: count=2
         *  4. OBSERVE B      → B: count=1 (add after spurious remove)
         *  5. OBSERVE C      → C: count=1
         *  6. REMOVE  A      → A: absent
         *  7. REMOVE  C      → C: absent
         *  8. OBSERVE C      → C: count=1 (fresh)
         *  9. OBSERVE C      → C: count=2
         * </pre>
         *
         * <h4>Expected final state</h4>
         * <ul>
         *   <li>Edge A — <b>absent</b> (removed at step 6)</li>
         *   <li>Edge B — <b>present</b>, requestCount=1 (added at step 4)</li>
         *   <li>Edge C — <b>present</b>, requestCount=2 (fresh pair added at steps 8–9)</li>
         * </ul>
         */
        @Test
        @DisplayName("nine interleaved events across three pairs converge to the predicted final state")
        void interleavedEventsForMultipleEdgesConvergeToExpectedFinalState() {
            publishObserve("il-A", "il-tgtA");        // 1  A: count=1
            publishRemove("il-B", "il-tgtB");         // 2  B: spurious
            publishObserve("il-A", "il-tgtA");        // 3  A: count=2
            publishObserve("il-B", "il-tgtB");        // 4  B: count=1
            publishObserve("il-C", "il-tgtC");        // 5  C: count=1
            publishRemove("il-A", "il-tgtA");         // 6  A: absent
            publishRemove("il-C", "il-tgtC");         // 7  C: absent
            publishObserve("il-C", "il-tgtC");        // 8  C: count=1 (fresh)
            publishObserve("il-C", "il-tgtC");        // 9  C: count=2

            awaitAsserted(() -> {
                // A must be absent
                assertThat(graphStore.getEdge("il-A", "il-tgtA")).isEmpty();
                // B must be present with exactly one observation
                Optional<Edge> edgeB = graphStore.getEdge("il-B", "il-tgtB");
                assertThat(edgeB).isPresent();
                assertThat(edgeB.get().getRequestCount()).isEqualTo(1L);
                // C must be present with exactly two fresh observations (history from step 5 discarded)
                Optional<Edge> edgeC = graphStore.getEdge("il-C", "il-tgtC");
                assertThat(edgeC).isPresent();
                assertThat(edgeC.get().getRequestCount()).isEqualTo(2L);
            }, Duration.ofSeconds(5));

            // Internal consistency: the count APIs must agree
            assertThat(graphStore.getAllEdges().size()).isEqualTo(graphStore.edgeCount());
            assertThat(graphStore.edgeCount()).isEqualTo(2); // B and C
        }

        /**
         * Five alternating OBSERVE / REMOVE events for a single edge, ending on
         * OBSERVE, must result in exactly one fresh edge.  The cycle verifies that
         * each add/remove pair is a clean state transition without accumulated history.
         *
         * <pre>
         * OBSERVE(no latency) → present  count=1
         * REMOVE              → absent
         * OBSERVE(no latency) → present  count=1  (fresh)
         * REMOVE              → absent
         * OBSERVE(42 ms)      → present  count=1  (fresh, 42 ms)   ← final state
         * </pre>
         */
        @Test
        @DisplayName("five alternating OBSERVE/REMOVE events ending on OBSERVE leave one fresh edge (42 ms)")
        void rapidAlternatingObserveRemoveConvergesToFinalObserveState() {
            publishObserve("alt-src", "alt-tgt");             // count=1
            publishRemove("alt-src", "alt-tgt");              // absent
            publishObserve("alt-src", "alt-tgt");             // count=1 fresh
            publishRemove("alt-src", "alt-tgt");              // absent
            publishObserveWithLatency("alt-src", "alt-tgt", 42); // final: count=1, avg=42 ms

            awaitEdgePresent("alt-src", "alt-tgt");

            Edge edge = graphStore.getEdge("alt-src", "alt-tgt").get();
            assertThat(edge.getRequestCount()).isEqualTo(1L);
            assertThat(edge.getAvgLatencyMs()).isEqualTo(42.0);
            assertThat(graphStore.getAllEdges().size()).isEqualTo(graphStore.edgeCount());
        }
    }

    // =========================================================================
    // Mixed Concurrent Publishing
    // =========================================================================

    @Nested
    @DisplayName("Mixed Concurrent Publishing")
    class ConcurrentPublishingTests {

        /**
         * Five concurrent producer threads each publish four events for their own
         * isolated edge pair: REMOVE (spurious) → OBSERVE → REMOVE → OBSERVE.
         *
         * <p>Since each thread uses a unique {@code (source, target)} pair and
         * each thread's calls are sequential (HTTP calls block), the relative
         * event order within each pair is preserved even though events from
         * different threads are interleaved in the queue.
         *
         * <p>The final event per thread is OBSERVE, so all five edges must be
         * present and internally consistent after the queue drains.
         */
        @Test
        @DisplayName("five concurrent producers with isolated edge pairs: all terminal OBSERVEs arrive")
        void concurrentProducersWithIsolatedEdgePairsAllTerminalObservesProcessed() {
            int threadCount = 5;
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final String src = "conc-" + i + "-src";
                final String tgt = "conc-" + i + "-tgt";
                futures.add(CompletableFuture.runAsync(() -> {
                    publishRemove(src, tgt);  // spurious: edge not yet created
                    publishObserve(src, tgt); // create edge
                    publishRemove(src, tgt);  // remove edge
                    publishObserve(src, tgt); // re-create — terminal state: PRESENT
                }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // All five terminal OBSERVEs must eventually be reflected in the graph
            awaitAsserted(() -> {
                for (int i = 0; i < threadCount; i++) {
                    assertThat(graphStore.getEdge("conc-" + i + "-src", "conc-" + i + "-tgt"))
                            .as("edge for thread %d", i)
                            .isPresent();
                }
            }, Duration.ofSeconds(10));

            assertThat(graphStore.edgeCount()).isEqualTo(threadCount);
            assertThat(graphStore.getAllEdges().size()).isEqualTo(graphStore.edgeCount());
        }

        /**
         * Eight concurrent producers all target the same {@code (source, target)}
         * pair: four publish OBSERVE, four publish REMOVE.  The test does not predict
         * the final edge state (it depends on queue interleaving), but it asserts:
         * <ol>
         *   <li>The pipeline did not crash — all events return 202 Accepted.</li>
         *   <li>{@code GraphStore} is internally consistent:
         *       {@code edgeCount()} equals {@code getAllEdges().size()}.</li>
         *   <li>The shared edge is in a valid binary state — present or absent
         *       — with structurally sound stats when present.</li>
         * </ol>
         */
        @Test
        @DisplayName("concurrent OBSERVE and REMOVE on the same edge pair yields internally consistent state")
        void concurrentObserveRemoveOnSharedEdgeYieldsConsistentState() {
            final String src = "shared-src";
            final String tgt = "shared-tgt";

            // 4 observer threads and 4 remover threads all targeting the same edge
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(CompletableFuture.runAsync(() -> publishObserve(src, tgt)));
                futures.add(CompletableFuture.runAsync(() -> publishRemove(src, tgt)));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Sentinel on a distinct pair guarantees all 8 preceding events are processed
            publishObserve("shared-sentinel", "shared-end");
            awaitEdgePresent("shared-sentinel", "shared-end");

            // Final state of the shared edge is indeterminate but must be internally valid
            Optional<Edge> sharedEdge = graphStore.getEdge(src, tgt);
            int sharedEdgeCount = sharedEdge.isPresent() ? 1 : 0;

            assertThat(graphStore.edgeCount()).isEqualTo(1 + sharedEdgeCount); // sentinel + shared
            assertThat(graphStore.getAllEdges().size()).isEqualTo(graphStore.edgeCount());

            // If the edge survived the concurrent removes, its fields must be structurally sound
            sharedEdge.ifPresent(e -> {
                assertThat(e.getSource()).isEqualTo(src);
                assertThat(e.getTarget()).isEqualTo(tgt);
                assertThat(e.getRequestCount()).isGreaterThanOrEqualTo(1L);
                assertThat(e.getAvgLatencyMs()).isGreaterThanOrEqualTo(0.0);
            });
        }
    }
}
