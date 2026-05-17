package e2e;

import com.defendermate.graph.graph.GraphStore;
import com.defendermate.graph.ingestion.EventRequest;
import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.EventType;
import com.defendermate.graph.model.dto.CriticalService;
import com.defendermate.graph.model.dto.CriticalServicesResponse;
import com.defendermate.graph.model.dto.CycleEntry;
import com.defendermate.graph.model.dto.CyclesResponse;
import com.defendermate.graph.model.dto.DependentsResponse;
import com.defendermate.graph.model.dto.ReachableResponse;
import com.defendermate.graph.model.dto.ShortestPathResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for all graph query endpoints.
 *
 * <p>Every test builds a deterministic graph by publishing exact events through the
 * ingestion controller, waits for consumer processing via Awaitility, then verifies
 * the HTTP response structure and exact computed values.  No mocks are used.
 *
 * <p>Tag: {@code e2e} — run individually with {@code mvn test -Dgroups=e2e}.
 */
@Tag("e2e")
@DisplayName("Graph Query")
class GraphQueryE2ETest extends BaseE2ETest {

    @Autowired
    private GraphStore graphStore;

    // =========================================================================
    // Test helpers
    // =========================================================================

    /** Publishes a single DEPENDENCY_OBSERVED event with no latency. */
    private void publishEdge(String source, String target) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.DEPENDENCY_OBSERVED, source, target, null, null, null));
    }

    /** Publishes a single DEPENDENCY_OBSERVED event with an explicit latency value. */
    private void publishEdgeWithLatency(String source, String target, long latencyMs) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.DEPENDENCY_OBSERVED, source, target, latencyMs, null, null));
    }

    /**
     * Blocks until the graph contains exactly {@code expected} edges.
     * Fails after 5 seconds if the count is not reached.
     */
    private void awaitEdgeCount(int expected) {
        awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(expected),
                Duration.ofSeconds(5));
    }

    // =========================================================================
    // Reachability
    // =========================================================================

    @Nested
    @DisplayName("Reachability")
    class ReachabilityTests {

        @Test
        @DisplayName("linear chain: downstream services reachable in DFS order with full paths")
        void linearChainReachabilityWithCorrectPaths() {
            // checkout-api → payment-service → bank-gateway
            publishEdge("checkout-api", "payment-service");
            publishEdge("payment-service", "bank-gateway");
            awaitEdgeCount(2);

            ResponseEntity<ReachableResponse> response = graphApi.reachable("checkout-api");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ReachableResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getSourceService()).isEqualTo("checkout-api");
            assertThat(body.getTotalReachableServices()).isEqualTo(2);
            assertThat(body.getPaths()).hasSize(2);

            // payment-service reached in one hop
            assertThat(body.getPaths())
                    .filteredOn(p -> "payment-service".equals(p.getTargetService()))
                    .singleElement()
                    .satisfies(p -> assertThat(p.getPath())
                            .containsExactly("checkout-api", "payment-service"));

            // bank-gateway reached transitively through payment-service
            assertThat(body.getPaths())
                    .filteredOn(p -> "bank-gateway".equals(p.getTargetService()))
                    .singleElement()
                    .satisfies(p -> assertThat(p.getPath())
                            .containsExactly("checkout-api", "payment-service", "bank-gateway"));
        }

        @Test
        @DisplayName("fork graph: root service reaches all services across both branches")
        void forkGraphReachesAllBranches() {
            // api-gateway → auth-service
            // api-gateway → data-service → database
            publishEdge("api-gateway", "auth-service");
            publishEdge("api-gateway", "data-service");
            publishEdge("data-service", "database");
            awaitEdgeCount(3);

            ResponseEntity<ReachableResponse> response = graphApi.reachable("api-gateway");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ReachableResponse body = response.getBody();
            assertThat(body.getTotalReachableServices()).isEqualTo(3);
            assertThat(body.getPaths())
                    .extracting(p -> p.getTargetService())
                    .containsExactlyInAnyOrder("auth-service", "data-service", "database");

            // database's path must pass through data-service
            assertThat(body.getPaths())
                    .filteredOn(p -> "database".equals(p.getTargetService()))
                    .singleElement()
                    .satisfies(p -> assertThat(p.getPath())
                            .containsExactly("api-gateway", "data-service", "database"));
        }

        @Test
        @DisplayName("service with no outgoing edges has zero reachable services")
        void leafServiceHasNoReachableServices() {
            publishEdge("upstream-svc", "leaf-svc");
            awaitEdgeCount(1);

            ResponseEntity<ReachableResponse> response = graphApi.reachable("leaf-svc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ReachableResponse body = response.getBody();
            assertThat(body.getSourceService()).isEqualTo("leaf-svc");
            assertThat(body.getTotalReachableServices()).isEqualTo(0);
            assertThat(body.getPaths()).isEmpty();
        }

        @Test
        @DisplayName("unknown service with no edges returns empty reachability")
        void unknownServiceReturnsEmptyReachability() {
            ResponseEntity<ReachableResponse> response = graphApi.reachable("nonexistent-svc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ReachableResponse body = response.getBody();
            assertThat(body.getSourceService()).isEqualTo("nonexistent-svc");
            assertThat(body.getTotalReachableServices()).isEqualTo(0);
            assertThat(body.getPaths()).isEmpty();
        }
    }

    // =========================================================================
    // Dependents
    // =========================================================================

    @Nested
    @DisplayName("Dependents")
    class DependentsTests {

        @Test
        @DisplayName("multiple upstream services identified as direct dependents with correct paths")
        void multipleDirectDependentsWithCorrectPaths() {
            // checkout-api → payment-service
            // order-service → payment-service
            publishEdge("checkout-api", "payment-service");
            publishEdge("order-service", "payment-service");
            awaitEdgeCount(2);

            ResponseEntity<DependentsResponse> response = graphApi.dependents("payment-service");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            DependentsResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTargetService()).isEqualTo("payment-service");
            assertThat(body.getTotalDependents()).isEqualTo(2);

            assertThat(body.getDependents())
                    .filteredOn(d -> "checkout-api".equals(d.getDependentService()))
                    .singleElement()
                    .satisfies(d -> assertThat(d.getPath())
                            .containsExactly("checkout-api", "payment-service"));

            assertThat(body.getDependents())
                    .filteredOn(d -> "order-service".equals(d.getDependentService()))
                    .singleElement()
                    .satisfies(d -> assertThat(d.getPath())
                            .containsExactly("order-service", "payment-service"));
        }

        @Test
        @DisplayName("transitive dependent discovered via reverse DFS with full upstream path")
        void transitiveDependentDiscoveredViaReverseDfs() {
            // top-api → mid-service → bottom-service
            // top-api is a transitive dependent of bottom-service
            publishEdge("top-api", "mid-service");
            publishEdge("mid-service", "bottom-service");
            awaitEdgeCount(2);

            ResponseEntity<DependentsResponse> response = graphApi.dependents("bottom-service");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            DependentsResponse body = response.getBody();
            assertThat(body.getTotalDependents()).isEqualTo(2);
            assertThat(body.getDependents())
                    .extracting(d -> d.getDependentService())
                    .containsExactlyInAnyOrder("top-api", "mid-service");

            // mid-service has a 1-hop path
            assertThat(body.getDependents())
                    .filteredOn(d -> "mid-service".equals(d.getDependentService()))
                    .singleElement()
                    .satisfies(d -> assertThat(d.getPath())
                            .containsExactly("mid-service", "bottom-service"));

            // top-api has a 2-hop transitive path
            assertThat(body.getDependents())
                    .filteredOn(d -> "top-api".equals(d.getDependentService()))
                    .singleElement()
                    .satisfies(d -> assertThat(d.getPath())
                            .containsExactly("top-api", "mid-service", "bottom-service"));
        }

        @Test
        @DisplayName("root service with no incoming edges returns zero dependents")
        void rootServiceHasNoDependents() {
            publishEdge("root-svc", "child-svc");
            awaitEdgeCount(1);

            ResponseEntity<DependentsResponse> response = graphApi.dependents("root-svc");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getTotalDependents()).isEqualTo(0);
            assertThat(response.getBody().getDependents()).isEmpty();
        }
    }

    // =========================================================================
    // Shortest Path
    // =========================================================================

    @Nested
    @DisplayName("Shortest Path")
    class ShortestPathTests {

        @Test
        @DisplayName("Dijkstra selects 40ms two-hop path over 110ms three-hop path")
        void dijkstraSelectsLowerLatencyRouteOverFewerHops() {
            // sp-A → sp-B (20ms) → sp-D (20ms)            total = 40ms  ← chosen
            // sp-A → sp-C  (5ms) → sp-E (5ms) → sp-D (100ms)  total = 110ms
            publishEdgeWithLatency("sp-A", "sp-B", 20);
            publishEdgeWithLatency("sp-B", "sp-D", 20);
            publishEdgeWithLatency("sp-A", "sp-C", 5);
            publishEdgeWithLatency("sp-C", "sp-E", 5);
            publishEdgeWithLatency("sp-E", "sp-D", 100);
            awaitEdgeCount(5);

            ResponseEntity<ShortestPathResponse> response = graphApi.shortestPath("sp-A", "sp-D");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ShortestPathResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getSourceService()).isEqualTo("sp-A");
            assertThat(body.getTargetService()).isEqualTo("sp-D");
            assertThat(body.isReachable()).isTrue();
            assertThat(body.getPath()).containsExactly("sp-A", "sp-B", "sp-D");
            assertThat(body.getTotalLatencyMs()).isEqualTo(40.0);
        }

        @Test
        @DisplayName("single direct edge: totalLatencyMs equals the edge's avgLatencyMs")
        void singleDirectEdgeReturnsExactLatency() {
            publishEdgeWithLatency("svc-src", "svc-tgt", 35);
            awaitEdgeCount(1);

            ResponseEntity<ShortestPathResponse> response = graphApi.shortestPath("svc-src", "svc-tgt");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ShortestPathResponse body = response.getBody();
            assertThat(body.isReachable()).isTrue();
            assertThat(body.getPath()).containsExactly("svc-src", "svc-tgt");
            assertThat(body.getTotalLatencyMs()).isEqualTo(35.0);
        }

        @Test
        @DisplayName("avgLatencyMs is the running average across multiple observations on the same edge")
        void avgLatencyIsRunningAverageAcrossMultipleObservations() {
            // 4 sequential observations: 0ms, 20ms, 40ms, 60ms
            // Running average:  count=1: 0.0  |  count=2: 10.0  |  count=3: 20.0  |  count=4: 30.0
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "avg-src", "avg-tgt", 0L, null, null));
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "avg-src", "avg-tgt", 20L, null, null));
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "avg-src", "avg-tgt", 40L, null, null));
            ingestionApi.publishEvent(new EventRequest(
                    null, EventType.DEPENDENCY_OBSERVED, "avg-src", "avg-tgt", 60L, null, null));

            // Wait for all 4 events to be processed and the running average to stabilise
            awaitAsserted(() -> {
                Optional<Edge> edge = graphStore.getEdge("avg-src", "avg-tgt");
                assertThat(edge).isPresent();
                assertThat(edge.get().getRequestCount()).isEqualTo(4L);
                assertThat(edge.get().getAvgLatencyMs()).isEqualTo(30.0);
            }, Duration.ofSeconds(5));

            ResponseEntity<ShortestPathResponse> response = graphApi.shortestPath("avg-src", "avg-tgt");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().isReachable()).isTrue();
            assertThat(response.getBody().getTotalLatencyMs()).isEqualTo(30.0);
        }

        @Test
        @DisplayName("disconnected graph: unreachable target returns reachable=false with empty path")
        void unreachableTargetReturnsNotReachable() {
            // Two disconnected components — no path from island-a to island-d
            publishEdge("island-a", "island-b");
            publishEdge("island-c", "island-d");
            awaitEdgeCount(2);

            ResponseEntity<ShortestPathResponse> response = graphApi.shortestPath("island-a", "island-d");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ShortestPathResponse body = response.getBody();
            assertThat(body.isReachable()).isFalse();
            assertThat(body.getPath()).isEmpty();
            assertThat(body.getTotalLatencyMs()).isEqualTo(-1.0);
        }

        @Test
        @DisplayName("source == target returns 400 Bad Request")
        void sourceEqualTargetReturnsBadRequest() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/api/graph/shortest-path?source=svc-x&target=svc-x", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // =========================================================================
    // Cycle Detection
    // =========================================================================

    @Nested
    @DisplayName("Cycle Detection")
    class CycleDetectionTests {

        @Test
        @DisplayName("three-node cycle A→B→C→A: exactly one cycle with length 3 is detected")
        void threeNodeCycleDetectedWithCorrectStructure() {
            // cycle-A → cycle-B → cycle-C → cycle-A
            publishEdge("cycle-A", "cycle-B");
            publishEdge("cycle-B", "cycle-C");
            publishEdge("cycle-C", "cycle-A");
            awaitEdgeCount(3);

            ResponseEntity<CyclesResponse> response = graphApi.cycles();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            CyclesResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTotalCycles()).isEqualTo(1);
            assertThat(body.getCycles()).hasSize(1);

            CycleEntry cycle = body.getCycles().get(0);
            assertThat(cycle.getCycleId()).isEqualTo(1);
            assertThat(cycle.getCycleLength()).isEqualTo(3);

            // path = [entryNode, ..., entryNode]  (cycleLength + 1 elements)
            List<String> path = cycle.getPath();
            assertThat(path).hasSize(4);
            assertThat(path.get(0)).isEqualTo(path.get(3));  // cycle closes

            // All three services appear in the cycle nodes
            assertThat(path.subList(0, 3))
                    .containsExactlyInAnyOrder("cycle-A", "cycle-B", "cycle-C");
        }

        @Test
        @DisplayName("acyclic diamond graph (A→B, A→C, B→D, C→D) reports zero cycles")
        void acyclicDiamondGraphHasNoCycles() {
            publishEdge("acyc-A", "acyc-B");
            publishEdge("acyc-A", "acyc-C");
            publishEdge("acyc-B", "acyc-D");
            publishEdge("acyc-C", "acyc-D");
            awaitEdgeCount(4);

            ResponseEntity<CyclesResponse> response = graphApi.cycles();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getTotalCycles()).isEqualTo(0);
            assertThat(response.getBody().getCycles()).isEmpty();
        }

        @Test
        @DisplayName("empty graph reports zero cycles")
        void emptyGraphHasNoCycles() {
            ResponseEntity<CyclesResponse> response = graphApi.cycles();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getTotalCycles()).isEqualTo(0);
            assertThat(response.getBody().getCycles()).isEmpty();
        }

        @Test
        @DisplayName("two independent cycles are each reported once with correct lengths")
        void twoIndependentCyclesEachReportedOnce() {
            // Cycle 1 (length 3): c2-X1 → c2-X2 → c2-X3 → c2-X1
            publishEdge("c2-X1", "c2-X2");
            publishEdge("c2-X2", "c2-X3");
            publishEdge("c2-X3", "c2-X1");
            // Cycle 2 (length 2): c2-Y1 → c2-Y2 → c2-Y1
            publishEdge("c2-Y1", "c2-Y2");
            publishEdge("c2-Y2", "c2-Y1");
            awaitEdgeCount(5);

            ResponseEntity<CyclesResponse> response = graphApi.cycles();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            CyclesResponse body = response.getBody();
            assertThat(body.getTotalCycles()).isEqualTo(2);
            assertThat(body.getCycles())
                    .extracting(CycleEntry::getCycleLength)
                    .containsExactlyInAnyOrder(3, 2);

            // Every cycle must be properly closed (first == last element)
            body.getCycles().forEach(c -> {
                List<String> p = c.getPath();
                assertThat(p).hasSize(c.getCycleLength() + 1);
                assertThat(p.get(0)).isEqualTo(p.get(c.getCycleLength()));
            });
        }
    }

    // =========================================================================
    // Critical Services
    // =========================================================================

    @Nested
    @DisplayName("Critical Services")
    class CriticalServicesTests {

        @Test
        @DisplayName("service with 3 dependents ranked first with exact metadata")
        void serviceWithMostDependentsRankedFirst() {
            // auth-service has 3 direct dependents
            publishEdge("checkout-api", "auth-service");
            publishEdge("mobile-api",   "auth-service");
            publishEdge("profile-api",  "auth-service");
            awaitEdgeCount(3);

            ResponseEntity<CriticalServicesResponse> response = graphApi.criticalServices(1);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            CriticalServicesResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getCriticalServices()).hasSize(1);

            CriticalService top = body.getCriticalServices().get(0);
            assertThat(top.getServiceName()).isEqualTo("auth-service");
            assertThat(top.getDependentServiceCount()).isEqualTo(3);
            // requestCount=1 per edge (single observation each) → sum = 3
            assertThat(top.getImpactedRequestCount()).isEqualTo(3L);
            // affectedDependents is sorted alphabetically
            assertThat(top.getAffectedDependents())
                    .containsExactly("checkout-api", "mobile-api", "profile-api");
        }

        @Test
        @DisplayName("k=2 returns two services ordered by dependent count descending")
        void topKServicesOrderedByDependentCount() {
            // svc-high has 3 dependents; svc-low has 1
            publishEdge("dep-A", "svc-high");
            publishEdge("dep-B", "svc-high");
            publishEdge("dep-C", "svc-high");
            publishEdge("dep-D", "svc-low");
            awaitEdgeCount(4);

            ResponseEntity<CriticalServicesResponse> response = graphApi.criticalServices(2);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<CriticalService> ranked = response.getBody().getCriticalServices();
            assertThat(ranked).hasSize(2);
            assertThat(ranked.get(0).getServiceName()).isEqualTo("svc-high");
            assertThat(ranked.get(0).getDependentServiceCount()).isEqualTo(3);
            assertThat(ranked.get(1).getServiceName()).isEqualTo("svc-low");
            assertThat(ranked.get(1).getDependentServiceCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("impactedRequestCount accumulates across multiple observations on the same edge")
        void impactedRequestCountAccumulatesAcrossObservations() {
            // 3 DEPENDENCY_OBSERVED events on the same (source, target) → requestCount=3
            publishEdge("heavy-client", "shared-svc");
            publishEdge("heavy-client", "shared-svc");
            publishEdge("heavy-client", "shared-svc");

            // Wait until the edge reflects all 3 observations
            awaitAsserted(() -> {
                Optional<Edge> edge = graphStore.getEdge("heavy-client", "shared-svc");
                assertThat(edge).isPresent();
                assertThat(edge.get().getRequestCount()).isEqualTo(3L);
            }, Duration.ofSeconds(5));

            ResponseEntity<CriticalServicesResponse> response = graphApi.criticalServices(1);

            CriticalService top = response.getBody().getCriticalServices().get(0);
            assertThat(top.getServiceName()).isEqualTo("shared-svc");
            assertThat(top.getDependentServiceCount()).isEqualTo(1);   // one unique source
            assertThat(top.getImpactedRequestCount()).isEqualTo(3L);   // 3 cumulative requests
        }

        @Test
        @DisplayName("k greater than qualifying service count returns all qualifying services")
        void kExceedingServiceCountReturnsAllQualifying() {
            publishEdge("only-client", "only-server");
            awaitEdgeCount(1);

            // k=100 but only one service has incoming edges
            ResponseEntity<CriticalServicesResponse> response = graphApi.criticalServices(100);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getCriticalServices()).hasSize(1);
            assertThat(response.getBody().getCriticalServices().get(0).getServiceName())
                    .isEqualTo("only-server");
        }

        @Test
        @DisplayName("k=0 returns 400 Bad Request")
        void zeroKReturnsBadRequest() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/api/graph/critical-services?k=0", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
