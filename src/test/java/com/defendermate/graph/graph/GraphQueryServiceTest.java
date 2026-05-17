package com.defendermate.graph.graph;

import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.EventTelemetry;
import com.defendermate.graph.model.dto.CriticalService;
import com.defendermate.graph.model.dto.CriticalServicesResponse;
import com.defendermate.graph.model.dto.CycleEntry;
import com.defendermate.graph.model.dto.CyclesResponse;
import com.defendermate.graph.model.dto.DependentPath;
import com.defendermate.graph.model.dto.DependentsResponse;
import com.defendermate.graph.model.dto.ReachablePath;
import com.defendermate.graph.model.dto.ReachableResponse;
import com.defendermate.graph.model.dto.ShortestPathResponse;
import com.defendermate.graph.model.dto.HealthMetrics;
import com.defendermate.graph.telemetry.TelemetryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class GraphQueryServiceTest {

    private GraphStore store;
    private TelemetryStore telemetryStore;
    private GraphQueryService service;

    @BeforeEach
    void setUp() {
        store         = new GraphStore();
        telemetryStore = new TelemetryStore();
        service       = new GraphQueryService(store, telemetryStore);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Adds a directed edge with neutral stats to the store. */
    private void addEdge(String source, String target) {
        store.putEdge(new Edge(source, target, 0.0, 0L, 0L, Instant.now()));
    }

    /**
     * Finds the {@link ReachablePath} for {@code target} in {@code response},
     * or fails the test if no such entry exists.
     */
    private ReachablePath pathTo(ReachableResponse response, String target) {
        return response.getPaths().stream()
                .filter(p -> target.equals(p.getTargetService()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No path found to: " + target));
    }

    // ==================================================================
    // Basic reachability
    // ==================================================================

    @Nested
    class BasicReachabilityTests {

        @Test
        void singleDirectDependency_onePathReturned() {
            addEdge("A", "B");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getSourceService()).isEqualTo("A");
            assertThat(response.getTotalReachableServices()).isEqualTo(1);
            assertThat(response.getPaths()).hasSize(1);
            assertThat(pathTo(response, "B").getPath()).containsExactly("A", "B");
        }

        @Test
        void twoDirectDependencies_twoPathsReturned() {
            addEdge("A", "B");
            addEdge("A", "C");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getTotalReachableServices()).isEqualTo(2);
            assertThat(response.getPaths())
                    .extracting(ReachablePath::getTargetService)
                    .containsExactlyInAnyOrder("B", "C");
            assertThat(pathTo(response, "B").getPath()).containsExactly("A", "B");
            assertThat(pathTo(response, "C").getPath()).containsExactly("A", "C");
        }

        @Test
        void linearChain_allHopsIncludedAsSeparatePaths() {
            // A → B → C → D
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "D");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getTotalReachableServices()).isEqualTo(3);
            assertThat(response.getPaths())
                    .extracting(ReachablePath::getTargetService)
                    .containsExactlyInAnyOrder("B", "C", "D");

            assertThat(pathTo(response, "B").getPath()).containsExactly("A", "B");
            assertThat(pathTo(response, "C").getPath()).containsExactly("A", "B", "C");
            assertThat(pathTo(response, "D").getPath()).containsExactly("A", "B", "C", "D");
        }

        @Test
        void specExample_checkoutApiGraph() {
            // checkout-api → payment-service → bank-gateway
            // checkout-api → inventory-service
            addEdge("checkout-api", "payment-service");
            addEdge("payment-service", "bank-gateway");
            addEdge("checkout-api", "inventory-service");

            ReachableResponse response = service.reachable("checkout-api");

            assertThat(response.getSourceService()).isEqualTo("checkout-api");
            assertThat(response.getTotalReachableServices()).isEqualTo(3);
            assertThat(response.getPaths())
                    .extracting(ReachablePath::getTargetService)
                    .containsExactlyInAnyOrder("payment-service", "bank-gateway", "inventory-service");

            assertThat(pathTo(response, "payment-service").getPath())
                    .containsExactly("checkout-api", "payment-service");
            assertThat(pathTo(response, "bank-gateway").getPath())
                    .containsExactly("checkout-api", "payment-service", "bank-gateway");
            assertThat(pathTo(response, "inventory-service").getPath())
                    .containsExactly("checkout-api", "inventory-service");
        }

        @Test
        void totalReachableServices_alwaysMatchesPathsListSize() {
            addEdge("A", "B");
            addEdge("A", "C");
            addEdge("B", "D");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getTotalReachableServices()).isEqualTo(response.getPaths().size());
        }

        @Test
        void allPaths_startWithSourceService() {
            addEdge("A", "B");
            addEdge("A", "C");
            addEdge("B", "D");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getPaths())
                    .allSatisfy(p -> assertThat(p.getPath().get(0)).isEqualTo("A"));
        }

        @Test
        void allPaths_endWithTargetService() {
            addEdge("A", "B");
            addEdge("A", "C");
            addEdge("B", "D");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getPaths()).allSatisfy(p -> {
                List<String> path = p.getPath();
                assertThat(path.get(path.size() - 1)).isEqualTo(p.getTargetService());
            });
        }

        @Test
        void sourceServiceNotInMetadataStore_traversalStillWorks() {
            // putEdge does not register services in metadataStore — reachable must
            // work purely from the edge index
            addEdge("A", "B");

            assertThatCode(() -> service.reachable("A")).doesNotThrowAnyException();
            assertThat(service.reachable("A").getTotalReachableServices()).isEqualTo(1);
        }
    }

    // ==================================================================
    // Edge cases
    // ==================================================================

    @Nested
    class EdgeCaseTests {

        @Test
        void unknownService_returnsEmptyResponse() {
            ReachableResponse response = service.reachable("does-not-exist");

            assertThat(response.getSourceService()).isEqualTo("does-not-exist");
            assertThat(response.getTotalReachableServices()).isEqualTo(0);
            assertThat(response.getPaths()).isEmpty();
        }

        @Test
        void serviceWithNoOutgoingEdges_returnsEmptyResponse() {
            // B is only a target — it has no outgoing edges
            addEdge("A", "B");

            ReachableResponse response = service.reachable("B");

            assertThat(response.getTotalReachableServices()).isEqualTo(0);
            assertThat(response.getPaths()).isEmpty();
        }

        @Test
        void emptyGraph_returnsEmptyResponse() {
            ReachableResponse response = service.reachable("A");

            assertThat(response.getTotalReachableServices()).isEqualTo(0);
            assertThat(response.getPaths()).isEmpty();
        }

        @Test
        void selfLoop_notFollowedAndNotInPaths() {
            // A → A: source is seeded into visited before DFS starts
            addEdge("A", "A");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getTotalReachableServices()).isEqualTo(0);
            assertThat(response.getPaths()).isEmpty();
        }

        @Test
        void directCycle_cycleEdgeNotFollowed() {
            // A → B → A
            addEdge("A", "B");
            addEdge("B", "A");

            ReachableResponse response = service.reachable("A");

            // B is reachable; A is in visited so the back-edge B→A is skipped
            assertThat(response.getTotalReachableServices()).isEqualTo(1);
            assertThat(pathTo(response, "B").getPath()).containsExactly("A", "B");
        }

        @Test
        void longerCycle_allNodesReachableExactlyOnce() {
            // A → B → C → A  (triangle)
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "A");

            ReachableResponse response = service.reachable("A");

            // B and C reachable; the back-edge C→A is skipped; A never appears as target
            assertThat(response.getTotalReachableServices()).isEqualTo(2);
            assertThat(response.getPaths())
                    .extracting(ReachablePath::getTargetService)
                    .containsExactlyInAnyOrder("B", "C");
            assertThat(pathTo(response, "B").getPath()).containsExactly("A", "B");
            assertThat(pathTo(response, "C").getPath()).containsExactly("A", "B", "C");
        }

        @Test
        void diamondGraph_sharedTargetAppearsExactlyOnce() {
            // A → B → D
            // A → C → D  (D reachable via two routes)
            addEdge("A", "B");
            addEdge("A", "C");
            addEdge("B", "D");
            addEdge("C", "D");

            ReachableResponse response = service.reachable("A");

            // B, C, and D each appear exactly once
            assertThat(response.getTotalReachableServices()).isEqualTo(3);
            assertThat(response.getPaths())
                    .extracting(ReachablePath::getTargetService)
                    .containsExactlyInAnyOrder("B", "C", "D");

            // D's path must go through exactly one intermediate (B or C), length 3
            ReachablePath dPath = pathTo(response, "D");
            assertThat(dPath.getPath()).satisfiesExactly(
                    first  -> assertThat(first).isEqualTo("A"),
                    middle -> assertThat(middle).isIn("B", "C"),
                    last   -> assertThat(last).isEqualTo("D")
            );
        }

        @Test
        void disconnectedGraph_onlyReachableServicesReturned() {
            // A → B  and  C → D  (two separate components)
            addEdge("A", "B");
            addEdge("C", "D");

            ReachableResponse response = service.reachable("A");

            // Only B is reachable from A; C and D are not
            assertThat(response.getTotalReachableServices()).isEqualTo(1);
            assertThat(response.getPaths())
                    .extracting(ReachablePath::getTargetService)
                    .containsExactly("B");
        }

        @Test
        void deepChain_allLevelsPresent() {
            // A → B → C → D → E
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "D");
            addEdge("D", "E");

            ReachableResponse response = service.reachable("A");

            assertThat(response.getTotalReachableServices()).isEqualTo(4);
            assertThat(pathTo(response, "B").getPath()).containsExactly("A", "B");
            assertThat(pathTo(response, "C").getPath()).containsExactly("A", "B", "C");
            assertThat(pathTo(response, "D").getPath()).containsExactly("A", "B", "C", "D");
            assertThat(pathTo(response, "E").getPath()).containsExactly("A", "B", "C", "D", "E");
        }

        @Test
        void pathsListReturnedIsNotNull() {
            ReachableResponse response = service.reachable("nobody");

            assertThat(response.getPaths()).isNotNull();
        }

        @Test
        void innerPathList_isImmutable() {
            addEdge("A", "B");

            ReachableResponse response = service.reachable("A");
            List<String> path = response.getPaths().get(0).getPath();

            assertThatThrownBy(() -> path.add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void cycleWithBranch_branchStillReachable() {
            // A → B → A  (cycle) but also  A → C  (branch)
            addEdge("A", "B");
            addEdge("B", "A");
            addEdge("A", "C");

            ReachableResponse response = service.reachable("A");

            // B and C both reachable; back-edge B→A skipped; A never a target
            assertThat(response.getTotalReachableServices()).isEqualTo(2);
            assertThat(response.getPaths())
                    .extracting(ReachablePath::getTargetService)
                    .containsExactlyInAnyOrder("B", "C");
        }
    }

    // ==================================================================
    // dependents() — basic behaviour
    // ==================================================================

    /**
     * Finds the {@link DependentPath} for {@code dependent} in {@code response},
     * or fails the test if no such entry exists.
     */
    private DependentPath dependentFor(DependentsResponse response, String dependent) {
        return response.getDependents().stream()
                .filter(d -> dependent.equals(d.getDependentService()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No dependent found for: " + dependent));
    }

    @Nested
    class DependentsBasicTests {

        @Test
        void singleDirectDependent_oneEntryReturned() {
            addEdge("A", "B");

            DependentsResponse response = service.dependents("B");

            assertThat(response.getTargetService()).isEqualTo("B");
            assertThat(response.getTotalDependents()).isEqualTo(1);
            assertThat(response.getDependents()).hasSize(1);
            assertThat(dependentFor(response, "A").getPath()).containsExactly("A", "B");
        }

        @Test
        void twoDirectDependents_twoEntriesReturned() {
            // checkout-api → payment-service
            // order-service → payment-service
            addEdge("checkout-api", "payment-service");
            addEdge("order-service", "payment-service");

            DependentsResponse response = service.dependents("payment-service");

            assertThat(response.getTotalDependents()).isEqualTo(2);
            assertThat(response.getDependents())
                    .extracting(DependentPath::getDependentService)
                    .containsExactlyInAnyOrder("checkout-api", "order-service");
            assertThat(dependentFor(response, "checkout-api").getPath())
                    .containsExactly("checkout-api", "payment-service");
            assertThat(dependentFor(response, "order-service").getPath())
                    .containsExactly("order-service", "payment-service");
        }

        @Test
        void specExample_paymentServiceGraph() {
            // checkout-api → payment-service
            // order-service → payment-service
            // payment-service → bank-gateway
            addEdge("checkout-api", "payment-service");
            addEdge("order-service",  "payment-service");
            addEdge("payment-service", "bank-gateway");

            DependentsResponse response = service.dependents("payment-service");

            assertThat(response.getTargetService()).isEqualTo("payment-service");
            assertThat(response.getTotalDependents()).isEqualTo(2);
            assertThat(response.getDependents())
                    .extracting(DependentPath::getDependentService)
                    .containsExactlyInAnyOrder("checkout-api", "order-service");
        }

        @Test
        void transitiveDependent_includedWithFullPath() {
            // A → B → C   (A transitively depends on C via B)
            addEdge("A", "B");
            addEdge("B", "C");

            DependentsResponse response = service.dependents("C");

            assertThat(response.getTotalDependents()).isEqualTo(2);
            assertThat(dependentFor(response, "B").getPath()).containsExactly("B", "C");
            assertThat(dependentFor(response, "A").getPath()).containsExactly("A", "B", "C");
        }

        @Test
        void deepChain_allUpstreamServicesPresent() {
            // A → B → C → D → E
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "D");
            addEdge("D", "E");

            DependentsResponse response = service.dependents("E");

            assertThat(response.getTotalDependents()).isEqualTo(4);
            assertThat(dependentFor(response, "D").getPath()).containsExactly("D", "E");
            assertThat(dependentFor(response, "C").getPath()).containsExactly("C", "D", "E");
            assertThat(dependentFor(response, "B").getPath()).containsExactly("B", "C", "D", "E");
            assertThat(dependentFor(response, "A").getPath()).containsExactly("A", "B", "C", "D", "E");
        }

        @Test
        void totalDependents_alwaysMatchesDependentsListSize() {
            addEdge("A", "C");
            addEdge("B", "C");
            addEdge("D", "A");

            DependentsResponse response = service.dependents("C");

            assertThat(response.getTotalDependents()).isEqualTo(response.getDependents().size());
        }

        @Test
        void allPaths_startWithDependentService() {
            addEdge("A", "C");
            addEdge("B", "C");

            DependentsResponse response = service.dependents("C");

            assertThat(response.getDependents())
                    .allSatisfy(d -> assertThat(d.getPath().get(0)).isEqualTo(d.getDependentService()));
        }

        @Test
        void allPaths_endWithTargetService() {
            addEdge("A", "C");
            addEdge("B", "C");
            addEdge("D", "A");

            DependentsResponse response = service.dependents("C");

            assertThat(response.getDependents()).allSatisfy(d -> {
                List<String> path = d.getPath();
                assertThat(path.get(path.size() - 1)).isEqualTo("C");
            });
        }
    }

    // ==================================================================
    // dependents() — edge cases
    // ==================================================================

    @Nested
    class DependentsEdgeCaseTests {

        @Test
        void unknownService_returnsEmptyResponse() {
            DependentsResponse response = service.dependents("does-not-exist");

            assertThat(response.getTargetService()).isEqualTo("does-not-exist");
            assertThat(response.getTotalDependents()).isEqualTo(0);
            assertThat(response.getDependents()).isEmpty();
        }

        @Test
        void serviceWithNoIncomingEdges_returnsEmptyResponse() {
            // A is a root — nothing depends on it
            addEdge("A", "B");

            DependentsResponse response = service.dependents("A");

            assertThat(response.getTotalDependents()).isEqualTo(0);
            assertThat(response.getDependents()).isEmpty();
        }

        @Test
        void emptyGraph_returnsEmptyResponse() {
            DependentsResponse response = service.dependents("X");

            assertThat(response.getTotalDependents()).isEqualTo(0);
            assertThat(response.getDependents()).isEmpty();
        }

        @Test
        void selfLoop_notFollowedAndNotInDependents() {
            // A → A: target is seeded into visited before reverse-DFS starts
            addEdge("A", "A");

            DependentsResponse response = service.dependents("A");

            assertThat(response.getTotalDependents()).isEqualTo(0);
            assertThat(response.getDependents()).isEmpty();
        }

        @Test
        void directCycle_cycleEdgeNotFollowed() {
            // A → B → A
            addEdge("A", "B");
            addEdge("B", "A");

            DependentsResponse response = service.dependents("A");

            // B depends on A directly; B→A is the only relevant incoming edge.
            // The back-edge A→B means A itself would cycle back — A is in visited so skipped.
            assertThat(response.getTotalDependents()).isEqualTo(1);
            assertThat(dependentFor(response, "B").getPath()).containsExactly("B", "A");
        }

        @Test
        void longerCycle_allUpstreamNodesExactlyOnce() {
            // A → B → C → A  (triangle; query dependents of A)
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "A");

            DependentsResponse response = service.dependents("A");

            // Incoming to A: C
            // Incoming to C: B
            // Incoming to B: A → already visited; stop
            assertThat(response.getTotalDependents()).isEqualTo(2);
            assertThat(response.getDependents())
                    .extracting(DependentPath::getDependentService)
                    .containsExactlyInAnyOrder("B", "C");
            assertThat(dependentFor(response, "C").getPath()).containsExactly("C", "A");
            assertThat(dependentFor(response, "B").getPath()).containsExactly("B", "C", "A");
        }

        @Test
        void diamondGraph_sharedDependentAppearsExactlyOnce() {
            // A → C   (A depends on C directly)
            // A → B → C  (A also depends on C transitively)
            addEdge("A", "C");
            addEdge("A", "B");
            addEdge("B", "C");

            DependentsResponse response = service.dependents("C");

            // A is reachable via two routes; must appear exactly once
            assertThat(response.getTotalDependents()).isEqualTo(2);
            assertThat(response.getDependents())
                    .extracting(DependentPath::getDependentService)
                    .containsExactlyInAnyOrder("A", "B");

            // A's path: whichever DFS-first route was taken — length 2 (direct) or 3 (via B)
            DependentPath aPath = dependentFor(response, "A");
            assertThat(aPath.getPath()).first().isEqualTo("A");
            assertThat(aPath.getPath()).last().isEqualTo("C");
        }

        @Test
        void disconnectedGraph_onlyReachableUpstreamReturned() {
            // A → B  and  C → D  (two separate components)
            addEdge("A", "B");
            addEdge("C", "D");

            DependentsResponse response = service.dependents("B");

            assertThat(response.getTotalDependents()).isEqualTo(1);
            assertThat(dependentFor(response, "A").getPath()).containsExactly("A", "B");
        }

        @Test
        void dependentsListReturnedIsNotNull() {
            DependentsResponse response = service.dependents("nobody");

            assertThat(response.getDependents()).isNotNull();
        }

        @Test
        void innerPathList_isImmutable() {
            addEdge("A", "B");

            DependentsResponse response = service.dependents("B");
            List<String> path = response.getDependents().get(0).getPath();

            assertThatThrownBy(() -> path.add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void cycleWithExtraUpstream_extraUpstreamStillFound() {
            // X → A → B → A  (cycle on A-B, but X depends on A)
            addEdge("X", "A");
            addEdge("A", "B");
            addEdge("B", "A");

            DependentsResponse response = service.dependents("A");

            // Incoming to A: X and B
            assertThat(response.getDependents())
                    .extracting(DependentPath::getDependentService)
                    .containsExactlyInAnyOrder("X", "B");
            assertThat(dependentFor(response, "X").getPath()).containsExactly("X", "A");
            assertThat(dependentFor(response, "B").getPath()).containsExactly("B", "A");
        }
    }

    // ==================================================================
    // Helpers for shortestPath() tests
    // ==================================================================

    /**
     * Adds an edge with an explicit avgLatencyMs weight.
     */
    private void addWeightedEdge(String source, String target, double latencyMs) {
        store.putEdge(new Edge(source, target, latencyMs, 1L, 0L, Instant.now()));
    }

    // ==================================================================
    // shortestPath() — basic behaviour
    // ==================================================================

    @Nested
    class ShortestPathBasicTests {

        @Test
        void specExample_twoPathsPicksLowerLatency() {
            // A → B = 20ms, B → D = 20ms  → total 40ms
            // A → C = 5ms,  C → E = 5ms, E → D = 100ms → total 110ms
            addWeightedEdge("A", "B", 20.0);
            addWeightedEdge("B", "D", 20.0);
            addWeightedEdge("A", "C",  5.0);
            addWeightedEdge("C", "E",  5.0);
            addWeightedEdge("E", "D", 100.0);

            ShortestPathResponse response = service.shortestPath("A", "D");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getSourceService()).isEqualTo("A");
            assertThat(response.getTargetService()).isEqualTo("D");
            assertThat(response.getPath()).containsExactly("A", "B", "D");
            assertThat(response.getTotalLatencyMs()).isEqualTo(40.0);
        }

        @Test
        void singleDirectEdge_pathLengthTwo() {
            addWeightedEdge("A", "B", 15.0);

            ShortestPathResponse response = service.shortestPath("A", "B");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "B");
            assertThat(response.getTotalLatencyMs()).isEqualTo(15.0);
        }

        @Test
        void linearChain_totalLatencyIsSum() {
            // A →(10)→ B →(20)→ C →(30)→ D
            addWeightedEdge("A", "B", 10.0);
            addWeightedEdge("B", "C", 20.0);
            addWeightedEdge("C", "D", 30.0);

            ShortestPathResponse response = service.shortestPath("A", "D");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "B", "C", "D");
            assertThat(response.getTotalLatencyMs()).isEqualTo(60.0);
        }

        @Test
        void higherLatencyDirectEdge_vsLowerLatencyTwoHopPath() {
            // Direct: A →(100)→ B
            // Two-hop: A →(1)→ X →(1)→ B
            addWeightedEdge("A", "B", 100.0);
            addWeightedEdge("A", "X",   1.0);
            addWeightedEdge("X", "B",   1.0);

            ShortestPathResponse response = service.shortestPath("A", "B");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "X", "B");
            assertThat(response.getTotalLatencyMs()).isEqualTo(2.0);
        }

        @Test
        void zeroWeightEdges_handledCorrectly() {
            addWeightedEdge("A", "B", 0.0);
            addWeightedEdge("B", "C", 0.0);

            ShortestPathResponse response = service.shortestPath("A", "C");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "B", "C");
            assertThat(response.getTotalLatencyMs()).isEqualTo(0.0);
        }

        @Test
        void sourceEqualsTarget_zeroLatencyPathToSelf() {
            addWeightedEdge("A", "B", 10.0);

            ShortestPathResponse response = service.shortestPath("A", "A");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A");
            assertThat(response.getTotalLatencyMs()).isEqualTo(0.0);
        }

        @Test
        void pathContainsSourceAsFirstAndTargetAsLast() {
            addWeightedEdge("A", "B", 5.0);
            addWeightedEdge("B", "C", 5.0);

            ShortestPathResponse response = service.shortestPath("A", "C");

            assertThat(response.getPath()).first().isEqualTo("A");
            assertThat(response.getPath()).last().isEqualTo("C");
        }

        @Test
        void pathList_isImmutable() {
            addWeightedEdge("A", "B", 5.0);

            ShortestPathResponse response = service.shortestPath("A", "B");

            assertThatThrownBy(() -> response.getPath().add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ==================================================================
    // shortestPath() — edge cases
    // ==================================================================

    @Nested
    class ShortestPathEdgeCaseTests {

        @Test
        void unknownSource_returnsUnreachable() {
            addWeightedEdge("A", "B", 10.0);

            ShortestPathResponse response = service.shortestPath("unknown", "B");

            assertThat(response.isReachable()).isFalse();
            assertThat(response.getPath()).isEmpty();
            assertThat(response.getTotalLatencyMs()).isEqualTo(-1.0);
        }

        @Test
        void unknownTarget_returnsUnreachable() {
            addWeightedEdge("A", "B", 10.0);

            ShortestPathResponse response = service.shortestPath("A", "unknown");

            assertThat(response.isReachable()).isFalse();
            assertThat(response.getPath()).isEmpty();
            assertThat(response.getTotalLatencyMs()).isEqualTo(-1.0);
        }

        @Test
        void emptyGraph_returnsUnreachable() {
            ShortestPathResponse response = service.shortestPath("A", "B");

            assertThat(response.isReachable()).isFalse();
            assertThat(response.getSourceService()).isEqualTo("A");
            assertThat(response.getTargetService()).isEqualTo("B");
            assertThat(response.getPath()).isEmpty();
            assertThat(response.getTotalLatencyMs()).isEqualTo(-1.0);
        }

        @Test
        void disconnectedComponents_returnsUnreachable() {
            // A → B  and  C → D  (target D not reachable from A)
            addWeightedEdge("A", "B", 10.0);
            addWeightedEdge("C", "D", 10.0);

            ShortestPathResponse response = service.shortestPath("A", "D");

            assertThat(response.isReachable()).isFalse();
            assertThat(response.getPath()).isEmpty();
        }

        @Test
        void directCycleDoesNotCauseInfiniteLoop() {
            // A →(5)→ B →(5)→ A
            addWeightedEdge("A", "B", 5.0);
            addWeightedEdge("B", "A", 5.0);

            // should terminate and still find A→B
            ShortestPathResponse response = service.shortestPath("A", "B");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "B");
            assertThat(response.getTotalLatencyMs()).isEqualTo(5.0);
        }

        @Test
        void longerCycleDoesNotCauseInfiniteLoop() {
            // A →(1)→ B →(1)→ C →(1)→ A  (triangle)
            addWeightedEdge("A", "B", 1.0);
            addWeightedEdge("B", "C", 1.0);
            addWeightedEdge("C", "A", 1.0);

            ShortestPathResponse response = service.shortestPath("A", "C");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "B", "C");
            assertThat(response.getTotalLatencyMs()).isEqualTo(2.0);
        }

        @Test
        void dijkstra_picksGlobalOptimumNotLocalGreedy() {
            // Greedy first-hop would pick A→C (cost 1) but the full path A→C→D costs 1000
            // Correct answer is A→B→D = 30
            addWeightedEdge("A", "B",   10.0);
            addWeightedEdge("B", "D",   20.0);
            addWeightedEdge("A", "C",    1.0);
            addWeightedEdge("C", "D",  999.0);

            ShortestPathResponse response = service.shortestPath("A", "D");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "B", "D");
            assertThat(response.getTotalLatencyMs()).isEqualTo(30.0);
        }

        @Test
        void unreachableResponse_pathIsEmptyNotNull() {
            ShortestPathResponse response = service.shortestPath("X", "Y");

            assertThat(response.getPath()).isNotNull().isEmpty();
        }

        @Test
        void selfLoop_doesNotAffectShortestPath() {
            // A →(A self-loop) and A →(5)→ B
            addWeightedEdge("A", "A", 0.0);
            addWeightedEdge("A", "B", 5.0);

            ShortestPathResponse response = service.shortestPath("A", "B");

            assertThat(response.isReachable()).isTrue();
            assertThat(response.getPath()).containsExactly("A", "B");
            assertThat(response.getTotalLatencyMs()).isEqualTo(5.0);
        }
    }

    // ==================================================================
    // Helpers for criticalServices() tests
    // ==================================================================

    /** Adds an edge with explicit requestCount (and zero error count). */
    private void addTrafficEdge(String source, String target, long requestCount) {
        store.putEdge(new Edge(source, target, 0.0, requestCount, 0L, Instant.now()));
    }

    /** Finds a CriticalService entry by serviceName, or fails the test. */
    private CriticalService criticalFor(CriticalServicesResponse response, String service) {
        return response.getCriticalServices().stream()
                .filter(cs -> service.equals(cs.getServiceName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No CriticalService found for: " + service));
    }

    // ==================================================================
    // criticalServices() — basic behaviour
    // ==================================================================

    @Nested
    class CriticalServicesBasicTests {

        @Test
        void specExample_authServiceIsTopRanked() {
            // checkout-api → auth-service
            // mobile-api   → auth-service
            // profile-api  → auth-service
            // auth-service → user-db
            addTrafficEdge("checkout-api", "auth-service", 100_000L);
            addTrafficEdge("mobile-api",   "auth-service",  80_000L);
            addTrafficEdge("profile-api",  "auth-service",  70_000L);
            addTrafficEdge("auth-service", "user-db",      250_000L);

            CriticalServicesResponse response = service.criticalServices(5);

            // auth-service has 3 direct dependents — it must be ranked first
            assertThat(response.getCriticalServices().get(0).getServiceName()).isEqualTo("auth-service");

            CriticalService auth = criticalFor(response, "auth-service");
            assertThat(auth.getDependentServiceCount()).isEqualTo(3);
            assertThat(auth.getImpactedRequestCount()).isEqualTo(250_000L);
            assertThat(auth.getAffectedDependents())
                    .containsExactlyInAnyOrder("checkout-api", "mobile-api", "profile-api");
        }

        @Test
        void k_limitsReturnedResults() {
            addTrafficEdge("A", "X", 10L);
            addTrafficEdge("B", "X", 10L); // X has 2 dependents
            addTrafficEdge("C", "Y", 10L); // Y has 1 dependent

            CriticalServicesResponse response = service.criticalServices(1);

            assertThat(response.getCriticalServices()).hasSize(1);
            assertThat(response.getCriticalServices().get(0).getServiceName()).isEqualTo("X");
        }

        @Test
        void kGreaterThanCandidates_returnsAllCandidates() {
            addTrafficEdge("A", "X", 10L);
            addTrafficEdge("B", "Y", 10L);

            CriticalServicesResponse response = service.criticalServices(100);

            // Only X and Y are edge targets
            assertThat(response.getCriticalServices()).hasSize(2);
        }

        @Test
        void ranking_primaryByDependentCount() {
            // Z: 3 dependents, small traffic
            addTrafficEdge("A", "Z", 1L);
            addTrafficEdge("B", "Z", 1L);
            addTrafficEdge("C", "Z", 1L);
            // W: 1 dependent, huge traffic
            addTrafficEdge("D", "W", 999_999L);

            CriticalServicesResponse response = service.criticalServices(2);

            // Z must rank above W: dependentCount (3) > dependentCount (1)
            assertThat(response.getCriticalServices().get(0).getServiceName()).isEqualTo("Z");
            assertThat(response.getCriticalServices().get(1).getServiceName()).isEqualTo("W");
        }

        @Test
        void ranking_tiebreakerByImpactedRequestCount() {
            // Both P and Q have 2 dependents; P has higher traffic
            addTrafficEdge("A", "P", 500L);
            addTrafficEdge("B", "P", 500L); // P total = 1000
            addTrafficEdge("C", "Q",  10L);
            addTrafficEdge("D", "Q",  10L); // Q total = 20

            CriticalServicesResponse response = service.criticalServices(2);

            assertThat(response.getCriticalServices().get(0).getServiceName()).isEqualTo("P");
            assertThat(response.getCriticalServices().get(1).getServiceName()).isEqualTo("Q");
        }

        @Test
        void impactedRequestCount_isSumOfAllIncomingEdgeTraffic() {
            addTrafficEdge("A", "S", 100L);
            addTrafficEdge("B", "S",  50L);
            addTrafficEdge("C", "S",  25L);

            CriticalService s = criticalFor(service.criticalServices(10), "S");

            assertThat(s.getImpactedRequestCount()).isEqualTo(175L);
        }

        @Test
        void affectedDependents_areSortedAlphabetically() {
            addTrafficEdge("zebra-svc",  "core", 1L);
            addTrafficEdge("alpha-svc",  "core", 1L);
            addTrafficEdge("middle-svc", "core", 1L);

            CriticalService core = criticalFor(service.criticalServices(5), "core");

            assertThat(core.getAffectedDependents())
                    .containsExactly("alpha-svc", "middle-svc", "zebra-svc");
        }

        @Test
        void servicesWithNoIncomingEdges_notIncluded() {
            // A is only a source (root), never a target
            addTrafficEdge("A", "B", 10L);

            CriticalServicesResponse response = service.criticalServices(10);

            assertThat(response.getCriticalServices())
                    .extracting(CriticalService::getServiceName)
                    .doesNotContain("A");
        }

        @Test
        void resultList_isImmutable() {
            addTrafficEdge("A", "B", 1L);

            CriticalServicesResponse response = service.criticalServices(5);

            assertThatThrownBy(() -> response.getCriticalServices().add(
                    new CriticalService("x", 0, 0L, List.of())))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void affectedDependentsList_isImmutable() {
            addTrafficEdge("A", "B", 1L);

            CriticalService b = criticalFor(service.criticalServices(5), "B");

            assertThatThrownBy(() -> b.getAffectedDependents().add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ==================================================================
    // criticalServices() — edge cases
    // ==================================================================

    @Nested
    class CriticalServicesEdgeCaseTests {

        @Test
        void kZero_returnsEmptyList() {
            addTrafficEdge("A", "B", 10L);

            CriticalServicesResponse response = service.criticalServices(0);

            assertThat(response.getCriticalServices()).isEmpty();
        }

        @Test
        void kNegative_returnsEmptyList() {
            addTrafficEdge("A", "B", 10L);

            CriticalServicesResponse response = service.criticalServices(-1);

            assertThat(response.getCriticalServices()).isEmpty();
        }

        @Test
        void emptyGraph_returnsEmptyList() {
            CriticalServicesResponse response = service.criticalServices(5);

            assertThat(response.getCriticalServices()).isNotNull().isEmpty();
        }

        @Test
        void singleEdge_singleCriticalService() {
            addTrafficEdge("client", "server", 42L);

            CriticalService s = criticalFor(service.criticalServices(1), "server");

            assertThat(s.getDependentServiceCount()).isEqualTo(1);
            assertThat(s.getImpactedRequestCount()).isEqualTo(42L);
            assertThat(s.getAffectedDependents()).containsExactly("client");
        }

        @Test
        void zeroTrafficEdges_impactedRequestCountIsZero() {
            addEdge("A", "B"); // addEdge uses 0L requestCount
            addEdge("C", "B");

            CriticalService b = criticalFor(service.criticalServices(5), "B");

            assertThat(b.getDependentServiceCount()).isEqualTo(2);
            assertThat(b.getImpactedRequestCount()).isEqualTo(0L);
        }

        @Test
        void disconnectedGraph_bothTargetsPresent() {
            // Component 1: A → B
            // Component 2: C → D
            addTrafficEdge("A", "B", 100L);
            addTrafficEdge("C", "D",  50L);

            CriticalServicesResponse response = service.criticalServices(10);

            assertThat(response.getCriticalServices())
                    .extracting(CriticalService::getServiceName)
                    .containsExactlyInAnyOrder("B", "D");
        }

        @Test
        void selfLoop_serviceCountsOwnLoop() {
            // A → A  (A depends on itself)
            addTrafficEdge("A", "A", 10L);

            CriticalService a = criticalFor(service.criticalServices(5), "A");

            assertThat(a.getDependentServiceCount()).isEqualTo(1);
            assertThat(a.getAffectedDependents()).containsExactly("A");
        }
    }

    // ==================================================================
    // Helpers for cycles() tests
    // ==================================================================

    /**
     * Returns the set of unique nodes in a cycle path (i.e. all elements
     * except the closing duplicate at the end).
     */
    private Set<String> cycleNodes(CycleEntry entry) {
        List<String> path = entry.getPath();
        return new HashSet<>(path.subList(0, path.size() - 1));
    }

    // ==================================================================
    // cycles() — basic behaviour
    // ==================================================================

    @Nested
    class CyclesBasicTests {

        @Test
        void simpleTriangle_oneCycleDetected() {
            // A → B → C → A
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "A");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(1);
            assertThat(response.getCycles()).hasSize(1);

            CycleEntry cycle = response.getCycles().get(0);
            assertThat(cycle.getCycleLength()).isEqualTo(3);
            assertThat(cycle.getPath()).hasSize(4);
            assertThat(cycle.getPath().get(0)).isEqualTo(cycle.getPath().get(3)); // closed
            assertThat(cycleNodes(cycle)).containsExactlyInAnyOrder("A", "B", "C");
        }

        @Test
        void twoNodeCycle_detectedCorrectly() {
            // A → B → A
            addEdge("A", "B");
            addEdge("B", "A");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(1);
            CycleEntry cycle = response.getCycles().get(0);
            assertThat(cycle.getCycleLength()).isEqualTo(2);
            assertThat(cycle.getPath()).hasSize(3);
            assertThat(cycle.getPath().get(0)).isEqualTo(cycle.getPath().get(2));
            assertThat(cycleNodes(cycle)).containsExactlyInAnyOrder("A", "B");
        }

        @Test
        void selfLoop_detectedAsCycleOfLengthOne() {
            // A → A
            addEdge("A", "A");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(1);
            CycleEntry cycle = response.getCycles().get(0);
            assertThat(cycle.getCycleLength()).isEqualTo(1);
            assertThat(cycle.getPath()).containsExactly("A", "A");
        }

        @Test
        void twoDisjointCycles_bothDetected() {
            // Cycle 1: A → B → A
            addEdge("A", "B");
            addEdge("B", "A");
            // Cycle 2: C → D → C
            addEdge("C", "D");
            addEdge("D", "C");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(2);

            // Verify each cycle contains the expected node pair
            List<Set<String>> nodeSets = response.getCycles().stream()
                    .map(e -> cycleNodes(e))
                    .collect(java.util.stream.Collectors.toList());
            assertThat(nodeSets)
                    .contains(Set.of("A", "B"))
                    .contains(Set.of("C", "D"));
        }

        @Test
        void diamondWithCycles_bothPathsDetected() {
            // A → B → D → A  (cycle 1)
            // A → C → D → A  (cycle 2, shares D and A)
            addEdge("A", "B");
            addEdge("A", "C");
            addEdge("B", "D");
            addEdge("C", "D");
            addEdge("D", "A");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(2);

            List<Set<String>> nodeSets = response.getCycles().stream()
                    .map(e -> cycleNodes(e))
                    .collect(java.util.stream.Collectors.toList());
            assertThat(nodeSets)
                    .contains(Set.of("A", "B", "D"))
                    .contains(Set.of("A", "C", "D"));
        }

        @Test
        void totalCycles_matchesCyclesListSize() {
            addEdge("A", "B");
            addEdge("B", "A");
            addEdge("C", "D");
            addEdge("D", "C");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(response.getCycles().size());
        }

        @Test
        void cycleId_assignedSequentiallyFromOne() {
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "A");

            CyclesResponse response = service.cycles();

            assertThat(response.getCycles().get(0).getCycleId()).isEqualTo(1);
        }

        @Test
        void cycleLength_equalsPathSizeMinusOne() {
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "A");

            CyclesResponse response = service.cycles();

            CycleEntry cycle = response.getCycles().get(0);
            assertThat(cycle.getCycleLength()).isEqualTo(cycle.getPath().size() - 1);
        }

        @Test
        void cyclePath_firstAndLastElementAreEqual() {
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "A");

            CyclesResponse response = service.cycles();

            response.getCycles().forEach(cycle -> {
                List<String> path = cycle.getPath();
                assertThat(path.get(0)).isEqualTo(path.get(path.size() - 1));
            });
        }

        @Test
        void sharedNodeInTwoCycles_appearsInBothCycles() {
            // auth-service is the hub of two separate incoming cycles
            // cycle 1: auth-service → X → auth-service
            // cycle 2: auth-service → Y → auth-service
            addEdge("auth-service", "X");
            addEdge("X", "auth-service");
            addEdge("auth-service", "Y");
            addEdge("Y", "auth-service");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(2);
            response.getCycles().forEach(cycle ->
                assertThat(cycleNodes(cycle)).contains("auth-service"));
        }
    }

    // ==================================================================
    // cycles() — edge cases
    // ==================================================================

    @Nested
    class CyclesEdgeCaseTests {

        @Test
        void emptyGraph_noCycles() {
            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(0);
            assertThat(response.getCycles()).isNotNull().isEmpty();
        }

        @Test
        void acyclicGraph_noCycles() {
            // A → B → C  (no back-edges)
            addEdge("A", "B");
            addEdge("B", "C");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(0);
            assertThat(response.getCycles()).isEmpty();
        }

        @Test
        void linearChain_noCycles() {
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "D");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(0);
        }

        @Test
        void disconnectedGraphOnlyOneCycleComponent_oneCycleReported() {
            // A → B  (no cycle)
            addEdge("A", "B");
            // C → D → C  (cycle)
            addEdge("C", "D");
            addEdge("D", "C");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(1);
            assertThat(cycleNodes(response.getCycles().get(0)))
                    .containsExactlyInAnyOrder("C", "D");
        }

        @Test
        void noDuplicateCycles_eachCycleReportedExactlyOnce() {
            // A → B → C → A: all three nodes are in getAllEdgeTargets(),
            // so DFS starts from each; canonicalization must prevent duplicates
            addEdge("A", "B");
            addEdge("B", "C");
            addEdge("C", "A");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(1);
        }

        @Test
        void cyclePath_isImmutable() {
            addEdge("A", "B");
            addEdge("B", "A");

            List<String> path = service.cycles().getCycles().get(0).getPath();

            assertThatThrownBy(() -> path.add("extra"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void cyclesList_isImmutable() {
            addEdge("A", "B");
            addEdge("B", "A");

            List<CycleEntry> cycles = service.cycles().getCycles();

            assertThatThrownBy(() -> cycles.add(new CycleEntry(99, List.of(), 0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void pureSourceNode_notStartingPointForCycle() {
            // X → A → B → A  (X has no incoming edge; cycle is A→B→A)
            addEdge("X", "A");
            addEdge("A", "B");
            addEdge("B", "A");

            CyclesResponse response = service.cycles();

            assertThat(response.getTotalCycles()).isEqualTo(1);
            assertThat(cycleNodes(response.getCycles().get(0)))
                    .containsExactlyInAnyOrder("A", "B");
        }
    }

    // ==================================================================
    // health() — helper
    // ==================================================================

    /** Records a telemetry event with a recent timestamp (within any test window). */
    private void recordEvent(String service, long latencyMs, EventStatus status) {
        telemetryStore.record(service,
                new EventTelemetry(Instant.now(), latencyMs, status));
    }

    // ==================================================================
    // health() — basic behaviour
    // ==================================================================

    @Nested
    class HealthBasicTests {

        @Test
        void allOkRequests_zeroFailureAndTimeoutRates() {
            recordEvent("svc", 100L, EventStatus.OK);
            recordEvent("svc", 150L, EventStatus.OK);
            recordEvent("svc", 200L, EventStatus.OK);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getFailureRate()).isEqualTo(0.0);
            assertThat(r.getTimeoutRate()).isEqualTo(0.0);
        }

        @Test
        void errorEvents_failureRateCalculatedCorrectly() {
            // 8 OK + 2 ERROR → failureRate = 0.2
            for (int i = 0; i < 8; i++) recordEvent("svc", 50L, EventStatus.OK);
            recordEvent("svc", 50L, EventStatus.ERROR);
            recordEvent("svc", 50L, EventStatus.ERROR);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getFailureRate()).isEqualTo(0.2);
            assertThat(r.getTimeoutRate()).isEqualTo(0.0);
        }

        @Test
        void timeoutEvents_trackedSeparatelyFromErrors() {
            // 8 OK + 1 ERROR + 1 TIMEOUT → failureRate = 0.1, timeoutRate = 0.1
            for (int i = 0; i < 8; i++) recordEvent("svc", 50L, EventStatus.OK);
            recordEvent("svc", 50L, EventStatus.ERROR);
            recordEvent("svc", 50L, EventStatus.TIMEOUT);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getFailureRate()).isEqualTo(0.1);
            assertThat(r.getTimeoutRate()).isEqualTo(0.1);
        }

        @Test
        void p95LatencyMs_delegatedToTelemetryStore() {
            // 20 events: latencies 1..20
            // TelemetryStore.p95: avg of bottom 95% = avg([1..19]) = 10
            for (long i = 1; i <= 20; i++) recordEvent("svc", i, EventStatus.OK);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getP95LatencyMs()).isEqualTo(10L);
        }

        @Test
        void serviceField_matchesInput() {
            recordEvent("payment-service", 50L, EventStatus.OK);

            HealthMetrics r = service.health("payment-service", Duration.ofMinutes(5));

            assertThat(r.getService()).isEqualTo("payment-service");
        }
    }

    // ==================================================================
    // health() — edge cases
    // ==================================================================

    @Nested
    class HealthEdgeCaseTests {

        @Test
        void emptyWindow_returnsAllZeros() {
            HealthMetrics r = service.health("unknown-svc", Duration.ofMinutes(5));

            assertThat(r.getP95LatencyMs()).isEqualTo(0L);
            assertThat(r.getFailureRate()).isEqualTo(0.0);
            assertThat(r.getTimeoutRate()).isEqualTo(0.0);
        }

        @Test
        void singleOkEvent_zeroFailureRate() {
            recordEvent("svc", 250L, EventStatus.OK);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getFailureRate()).isEqualTo(0.0);
            assertThat(r.getP95LatencyMs()).isEqualTo(250L);
        }

        @Test
        void singleErrorEvent_failureRateIsOne() {
            recordEvent("svc", 50L, EventStatus.ERROR);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getFailureRate()).isEqualTo(1.0);
            assertThat(r.getTimeoutRate()).isEqualTo(0.0);
        }

        @Test
        void allTimeoutEvents_timeoutRateIsOne() {
            recordEvent("svc", 50L, EventStatus.TIMEOUT);
            recordEvent("svc", 50L, EventStatus.TIMEOUT);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getTimeoutRate()).isEqualTo(1.0);
            assertThat(r.getFailureRate()).isEqualTo(0.0);
        }

        @Test
        void isolatedServices_metricsDoNotCrossContaminate() {
            recordEvent("alpha", 100L, EventStatus.OK);
            recordEvent("beta",  100L, EventStatus.ERROR);

            HealthMetrics alpha = service.health("alpha", Duration.ofMinutes(5));
            HealthMetrics beta  = service.health("beta",  Duration.ofMinutes(5));

            assertThat(alpha.getFailureRate()).isEqualTo(0.0);
            assertThat(beta.getFailureRate()).isEqualTo(1.0);
        }

        @Test
        void p95Latency_singleValue_returnsThatValue() {
            recordEvent("svc", 777L, EventStatus.OK);

            HealthMetrics r = service.health("svc", Duration.ofMinutes(5));

            assertThat(r.getP95LatencyMs()).isEqualTo(777L);
        }
    }
}
