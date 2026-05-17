package e2e.client;

import com.defendermate.graph.ingestion.EventRequest;
import com.defendermate.graph.ingestion.IngestionSummary;
import com.defendermate.graph.model.Event;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

/**
 * Typed HTTP client for {@code /api/ingestion} endpoints.
 *
 * <p>Mirrors the production {@code IngestionController}, covering both the
 * bulk-generation endpoint and the single hand-crafted event endpoint.
 * The {@link TestRestTemplate} is pre-configured by Spring Boot to resolve
 * paths against {@code http://localhost:{port}}.
 *
 * <h3>Typical usage (inside a subclass of {@link e2e.BaseE2ETest})</h3>
 * <pre>{@code
 * // Publish one directed dependency edge
 * EventRequest req = new EventRequest(null, EventType.DEPENDENCY_OBSERVED,
 *         "checkout-api", "payment-service", null, null, null);
 * ResponseEntity<Event> response = ingestionApi.publishEvent(req);
 * assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
 *
 * // Wait for the consumer to process the event, then query the graph
 * awaitEmptyQueue();
 * }</pre>
 */
public class IngestionApiClient {

    private static final String BASE = "/api/ingestion";

    private final TestRestTemplate restTemplate;

    public IngestionApiClient(TestRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // -------------------------------------------------------------------------
    // Bulk generation
    // -------------------------------------------------------------------------

    /**
     * {@code POST /api/ingestion/publish?count={count}}
     *
     * <p>Asks the server to generate {@code count} random events and publish them
     * through the configured producer threads.
     *
     * @param count number of events to generate; must be &gt; 0
     * @return {@code 200 OK} with a summary of the publish operation
     */
    public ResponseEntity<IngestionSummary> publish(int count) {
        String url = BASE + "/publish?count={count}";
        return restTemplate.postForEntity(url, null, IngestionSummary.class, count);
    }

    // -------------------------------------------------------------------------
    // Single event
    // -------------------------------------------------------------------------

    /**
     * {@code POST /api/ingestion/event}
     *
     * <p>Publishes a single hand-crafted event into the ingestion queue. Useful for
     * building precise graph fixtures in E2E tests without relying on randomised data.
     *
     * <p>Required fields: {@code source}, {@code type}. Optional fields:
     * {@code eventId} (auto-assigned if null), {@code target}, {@code latencyMs},
     * {@code status} (defaults to {@code OK}), {@code timestamp} (defaults to now).
     *
     * @param request the event to publish
     * @return {@code 202 Accepted} with the enqueued {@link Event} (including the assigned {@code eventId})
     */
    public ResponseEntity<Event> publishEvent(EventRequest request) {
        return restTemplate.postForEntity(BASE + "/event", request, Event.class);
    }
}
