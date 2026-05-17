package e2e;

import com.defendermate.graph.ServiceDependencyAnalyzerApplication;
import com.defendermate.graph.graph.GraphStore;
import com.defendermate.graph.ingestion.EventProcessor;
import com.defendermate.graph.ingestion.EventQueueManager;
import com.defendermate.graph.telemetry.TelemetryStore;
import e2e.client.GraphApiClient;
import e2e.client.IngestionApiClient;
import org.awaitility.Awaitility;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for end-to-end tests of the Service Dependency Analyzer.
 *
 * <p>Starts a complete Spring Boot application on a random port (using the {@code e2e} profile
 * which disables snapshot I/O and reduces queue/thread counts for test speed). Subclasses get
 * pre-wired typed API clients and a clean, empty in-memory graph before each test method.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * class MyE2ETest extends BaseE2ETest {
 *
 *     @Test
 *     void graphReflectsDependency() {
 *         ingestionApi.publishEvent(new EventRequest(null, EventType.DEPENDENCY_OBSERVED,
 *                 "svc-a", "svc-b", null, null, null));
 *         awaitEmptyQueue();
 *         assertThat(graphApi.reachable("svc-a").getBody().getReachable()).containsKey("svc-b");
 *     }
 * }
 * }</pre>
 *
 * <h3>State reset</h3>
 * <p>Before each test the following in-memory state is cleared:
 * <ul>
 *   <li>All graph nodes and edges ({@link GraphStore})</li>
 *   <li>All telemetry records ({@link TelemetryStore})</li>
 *   <li>The processed-event-ID dedup set ({@link EventProcessor})</li>
 * </ul>
 * The ingestion queue is drained first so that events from a previous test cannot
 * contaminate the next one.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ServiceDependencyAnalyzerApplication.class)
@ActiveProfiles("e2e")
public abstract class BaseE2ETest {

    // -------------------------------------------------------------------------
    // Infrastructure wiring
    // -------------------------------------------------------------------------

    @LocalServerPort
    protected int port;

    /** Pre-configured to resolve against http://localhost:{port} automatically. */
    @Autowired
    protected TestRestTemplate restTemplate;

    // -------------------------------------------------------------------------
    // Typed API clients (initialised in setUp())
    // -------------------------------------------------------------------------

    protected GraphApiClient graphApi;
    protected IngestionApiClient ingestionApi;

    // -------------------------------------------------------------------------
    // Stores wired for state reset
    // -------------------------------------------------------------------------

    @Autowired
    private GraphStore graphStore;

    @Autowired
    private TelemetryStore telemetryStore;

    @Autowired
    private EventProcessor eventProcessor;

    @Autowired
    private EventQueueManager queueManager;

    // -------------------------------------------------------------------------
    // JUnit lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    final void setUp() {
        // Clients are cheap POJOs: recreate each test so subclasses can safely
        // replace restTemplate between tests if needed.
        graphApi = new GraphApiClient(restTemplate);
        ingestionApi = new IngestionApiClient(restTemplate);

        // Wait for any events still in-flight from a previous test, then clear state.
        awaitEmptyQueue(Duration.ofSeconds(10));
        resetGraphState();
    }

    // -------------------------------------------------------------------------
    // Await helpers
    // -------------------------------------------------------------------------

    /**
     * Blocks until the ingestion queue is empty (all published events have been
     * picked up by a consumer thread), up to {@code timeout}.
     *
     * <p>Call this after publishing events via {@link IngestionApiClient} before
     * asserting on graph or telemetry state.
     */
    protected void awaitEmptyQueue(Duration timeout) {
        Awaitility.await("ingestion queue to drain")
                .atMost(timeout)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .until(() -> queueManager.size() == 0);
    }

    /**
     * Convenience overload: waits up to 5 seconds for the ingestion queue to drain.
     */
    protected void awaitEmptyQueue() {
        awaitEmptyQueue(Duration.ofSeconds(5));
    }

    /**
     * Repeatedly evaluates {@code assertion} until it passes without throwing, or until
     * {@code timeout} elapses (at which point the last assertion failure is re-thrown).
     *
     * <p>Use this for graph-state assertions that depend on asynchronous processing:
     * <pre>{@code
     * awaitAsserted(() -> assertThat(graphStore.edgeCount()).isEqualTo(2), Duration.ofSeconds(5));
     * }</pre>
     */
    protected void awaitAsserted(ThrowingRunnable assertion, Duration timeout) {
        Awaitility.await()
                .atMost(timeout)
                .untilAsserted(assertion);
    }

    // -------------------------------------------------------------------------
    // State reset
    // -------------------------------------------------------------------------

    private void resetGraphState() {
        // Collect every node name referenced in any edge or metadata entry.
        Set<String> nodes = new HashSet<>(graphStore.getAllServices());
        graphStore.getAllEdges().forEach(e -> {
            nodes.add(e.getSource());
            nodes.add(e.getTarget());
        });
        // removeService() cascades: removes the node's metadata and all its edges.
        nodes.forEach(graphStore::removeService);

        telemetryStore.clear();
        eventProcessor.clearProcessedEventIds();
    }
}
