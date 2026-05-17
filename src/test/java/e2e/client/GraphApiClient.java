package e2e.client;

import com.defendermate.graph.model.dto.CriticalServicesResponse;
import com.defendermate.graph.model.dto.CyclesResponse;
import com.defendermate.graph.model.dto.DependentsResponse;
import com.defendermate.graph.model.dto.HealthMetrics;
import com.defendermate.graph.model.dto.ReachableResponse;
import com.defendermate.graph.model.dto.ShortestPathResponse;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * Typed HTTP client for {@code /api/graph} endpoints.
 *
 * <p>Each method maps one-to-one to a controller endpoint and returns a
 * {@link ResponseEntity} so callers can inspect both the response body and
 * the HTTP status code. The {@link TestRestTemplate} is pre-configured by
 * Spring Boot to resolve paths against {@code http://localhost:{port}}.
 *
 * <h3>Typical usage (inside a subclass of {@link e2e.BaseE2ETest})</h3>
 * <pre>{@code
 * ResponseEntity<ReachableResponse> response = graphApi.reachable("checkout-api");
 * assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
 * assertThat(response.getBody().getReachable()).containsKey("payment-service");
 * }</pre>
 */
public class GraphApiClient {

    private static final String BASE = "/api/graph";

    private final TestRestTemplate restTemplate;

    public GraphApiClient(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // -------------------------------------------------------------------------
    // Reachability
    // -------------------------------------------------------------------------

    /**
     * {@code GET /api/graph/reachable/{service}}
     *
     * @param service the source service to start BFS/DFS from
     */
    public ResponseEntity<ReachableResponse> reachable(String service) {
        return restTemplate.getForEntity(BASE + "/reachable/{service}", ReachableResponse.class, service);
    }

    // -------------------------------------------------------------------------
    // Dependents
    // -------------------------------------------------------------------------

    /**
     * {@code GET /api/graph/dependents/{service}}
     *
     * @param service the target service whose upstream dependents are requested
     */
    public ResponseEntity<DependentsResponse> dependents(String service) {
        return restTemplate.getForEntity(BASE + "/dependents/{service}", DependentsResponse.class, service);
    }

    // -------------------------------------------------------------------------
    // Shortest path
    // -------------------------------------------------------------------------

    /**
     * {@code GET /api/graph/shortest-path?source={source}&target={target}}
     */
    public ResponseEntity<ShortestPathResponse> shortestPath(String source, String target) {
        String url = BASE + "/shortest-path?source={source}&target={target}";
        return restTemplate.getForEntity(url, ShortestPathResponse.class, source, target);
    }

    // -------------------------------------------------------------------------
    // Critical services
    // -------------------------------------------------------------------------

    /**
     * {@code GET /api/graph/critical-services?k={k}}
     *
     * @param k the maximum number of critical services to return
     */
    public ResponseEntity<CriticalServicesResponse> criticalServices(int k) {
        return restTemplate.getForEntity(BASE + "/critical-services?k={k}", CriticalServicesResponse.class, k);
    }

    // -------------------------------------------------------------------------
    // Cycles
    // -------------------------------------------------------------------------

    /**
     * {@code GET /api/graph/cycles}
     */
    public ResponseEntity<CyclesResponse> cycles() {
        return restTemplate.getForEntity(BASE + "/cycles", CyclesResponse.class);
    }

    // -------------------------------------------------------------------------
    // Health metrics
    // -------------------------------------------------------------------------

    /**
     * {@code GET /api/graph/health/{service}?window={window}}
     *
     * @param service the service to retrieve health metrics for
     * @param window  time window string, e.g. {@code "10m"} or {@code "2h"}
     */
    public ResponseEntity<HealthMetrics> health(String service, String window) {
        String url = BASE + "/health/{service}?window={window}";
        return restTemplate.getForEntity(url, HealthMetrics.class, service, window);
    }

    /**
     * Overload that omits the {@code window} parameter (server default is used).
     */
    public ResponseEntity<HealthMetrics> health(String service) {
        return restTemplate.getForEntity(BASE + "/health/{service}", HealthMetrics.class, service);
    }
}
