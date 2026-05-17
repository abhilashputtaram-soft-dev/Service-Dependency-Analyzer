package e2e;

import com.defendermate.graph.ingestion.EventRequest;
import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.EventTelemetry;
import com.defendermate.graph.model.EventType;
import com.defendermate.graph.model.dto.HealthMetrics;
import com.defendermate.graph.telemetry.TelemetryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end tests for {@code GET /api/graph/health/{service}?window={window}}.
 *
 * <p>Telemetry is injected deterministically via HEARTBEAT events through the
 * single-event ingestion API ({@code POST /api/ingestion/event}).  HEARTBEAT
 * events update the telemetry store for the source service without mutating
 * graph topology, giving an isolated, controllable input surface.
 *
 * <p>Where stale-event behaviour must be tested, records are inserted directly
 * into {@link TelemetryStore} with back-dated {@link Instant} timestamps.
 *
 * <h3>p95 algorithm (TelemetryStore)</h3>
 * <pre>
 *   sorted   = latencies sorted ascending
 *   count    = (int) Math.ceil(0.95 × n)      // ceil, NOT floor
 *   p95      = (long) avg( sorted[0..count) )  // average of bottom-95%
 * </pre>
 * For {@code n ≤ 19}: {@code ceil(0.95n) = n} → all values are included,
 * so p95 equals the arithmetic mean.
 * For {@code n = 20}: {@code ceil(0.95 × 20) = 19} → the single highest value
 * is excluded, demonstrating meaningful outlier suppression.
 *
 * <p>Tag: {@code e2e} — run individually with {@code mvn test -Dgroups=e2e}.
 */
@Tag("e2e")
@DisplayName("Health Query")
class HealthQueryE2ETest extends BaseE2ETest {

    // =========================================================================
    // Infrastructure
    // =========================================================================

    @Autowired
    private TelemetryStore telemetryStore;

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Publishes a HEARTBEAT event that carries latency and status.
     * EventProcessor records this as a telemetry entry for {@code service}
     * without mutating any graph edge.
     */
    private void publishTelemetry(String service, long latencyMs, EventStatus status) {
        ingestionApi.publishEvent(new EventRequest(
                null, EventType.HEARTBEAT, service, null, latencyMs, status, null));
    }

    /**
     * Blocks until {@link TelemetryStore#getWindow} for {@code service} over
     * a 5-minute look-back returns exactly {@code expectedCount} entries.
     * Fails after 5 seconds.
     */
    private void awaitTelemetryCount(String service, int expectedCount) {
        awaitAsserted(
                () -> assertThat(telemetryStore.getWindow(service, Duration.ofMinutes(5)).size())
                        .isEqualTo(expectedCount),
                Duration.ofSeconds(5));
    }

    // =========================================================================
    // Successful Requests
    // =========================================================================

    @Nested
    @DisplayName("Successful Requests")
    class SuccessfulRequestsTests {

        /**
         * Four OK events with distinct latencies.
         *
         * <pre>
         * latencies = [50, 100, 150, 200]
         * n = 4, count = ceil(0.95 × 4) = ceil(3.8) = 4   → all included
         * p95 = avg(50+100+150+200) = 500 / 4 = 125 ms
         * failureRate = 0 / 4 = 0.0
         * timeoutRate = 0 / 4 = 0.0
         * </pre>
         */
        @Test
        @DisplayName("four OK events: p95 equals arithmetic mean, both error rates zero")
        void allOkEventsYieldZeroErrorRates() {
            publishTelemetry("svc-all-ok", 50L, EventStatus.OK);
            publishTelemetry("svc-all-ok", 100L, EventStatus.OK);
            publishTelemetry("svc-all-ok", 150L, EventStatus.OK);
            publishTelemetry("svc-all-ok", 200L, EventStatus.OK);
            awaitTelemetryCount("svc-all-ok", 4);

            ResponseEntity<HealthMetrics> response = graphApi.health("svc-all-ok", "5m");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            HealthMetrics body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getService()).isEqualTo("svc-all-ok");
            assertThat(body.getP95LatencyMs()).isEqualTo(125L);
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }

        /**
         * Single OK event: healthy baseline with exact single-value p95.
         *
         * <pre>
         * n = 1, count = ceil(0.95) = 1 → p95 = 75 ms
         * </pre>
         */
        @Test
        @DisplayName("single OK event: healthy baseline — p95 equals single latency value")
        void singleOkEventIsHealthy() {
            publishTelemetry("svc-single-ok", 75L, EventStatus.OK);
            awaitTelemetryCount("svc-single-ok", 1);

            HealthMetrics body = graphApi.health("svc-single-ok", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(75L);
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }
    }

    // =========================================================================
    // Failed Requests
    // =========================================================================

    @Nested
    @DisplayName("Failed Requests")
    class FailedRequestsTests {

        /**
         * Three ERROR events — failure rate must be 1.0.
         *
         * <pre>
         * latencies = [100, 200, 300]
         * n = 3, count = ceil(2.85) = 3 → p95 = 200 ms
         * failureRate = 3 / 3 = 1.0
         * timeoutRate = 0 / 3 = 0.0
         * </pre>
         */
        @Test
        @DisplayName("all ERROR events: failure rate is 1.0, timeout rate is 0.0")
        void allErrorEventsYieldFullFailureRate() {
            publishTelemetry("svc-all-error", 100L, EventStatus.ERROR);
            publishTelemetry("svc-all-error", 200L, EventStatus.ERROR);
            publishTelemetry("svc-all-error", 300L, EventStatus.ERROR);
            awaitTelemetryCount("svc-all-error", 3);

            HealthMetrics body = graphApi.health("svc-all-error", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(200L);
            assertThat(body.getFailureRate()).isEqualTo(1.0);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }

        /**
         * Two OK + two ERROR events: failure rate = 0.5.
         *
         * <pre>
         * latencies = [50, 100, 200, 400] sorted
         * n = 4, count = 4 → p95 = avg(750) = 187 ms (750/4 = 187.5 → (long) 187)
         * failureRate = 2 / 4 = 0.5
         * timeoutRate = 0 / 4 = 0.0
         * </pre>
         */
        @Test
        @DisplayName("two OK + two ERROR events: failure rate is exactly 0.5")
        void partialFailureRateMatchesErrorFraction() {
            publishTelemetry("svc-partial-error", 50L, EventStatus.OK);
            publishTelemetry("svc-partial-error", 100L, EventStatus.OK);
            publishTelemetry("svc-partial-error", 200L, EventStatus.ERROR);
            publishTelemetry("svc-partial-error", 400L, EventStatus.ERROR);
            awaitTelemetryCount("svc-partial-error", 4);

            HealthMetrics body = graphApi.health("svc-partial-error", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(187L);   // (long)(750/4.0) = 187
            assertThat(body.getFailureRate()).isEqualTo(0.5);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }
    }

    // =========================================================================
    // Timeout Requests
    // =========================================================================

    @Nested
    @DisplayName("Timeout Requests")
    class TimeoutRequestsTests {

        /**
         * Two TIMEOUT events — timeout rate must be 1.0.
         *
         * <pre>
         * latencies = [500, 1000]
         * n = 2, count = ceil(1.9) = 2 → p95 = avg(1500) = 750 ms
         * failureRate = 0.0
         * timeoutRate = 2 / 2 = 1.0
         * </pre>
         */
        @Test
        @DisplayName("all TIMEOUT events: timeout rate is 1.0, failure rate is 0.0")
        void allTimeoutEventsYieldFullTimeoutRate() {
            publishTelemetry("svc-all-timeout", 500L, EventStatus.TIMEOUT);
            publishTelemetry("svc-all-timeout", 1000L, EventStatus.TIMEOUT);
            awaitTelemetryCount("svc-all-timeout", 2);

            HealthMetrics body = graphApi.health("svc-all-timeout", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(750L);
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isEqualTo(1.0);
        }

        /**
         * Four OK + two TIMEOUT events: timeout rate = 2/6.
         *
         * <pre>
         * latencies = [50, 60, 70, 80, 800, 900] sorted
         * n = 6, count = ceil(5.7) = 6 → p95 = avg(1960) = 326 ms (1960/6 = 326.6̄ → (long) 326)
         * failureRate = 0 / 6 = 0.0
         * timeoutRate = 2 / 6 ≈ 0.3333...
         * </pre>
         */
        @Test
        @DisplayName("four OK + two TIMEOUT events: timeout rate matches 2/6 fraction")
        void partialTimeoutRateMatchesTimeoutFraction() {
            publishTelemetry("svc-partial-timeout", 50L, EventStatus.OK);
            publishTelemetry("svc-partial-timeout", 60L, EventStatus.OK);
            publishTelemetry("svc-partial-timeout", 70L, EventStatus.OK);
            publishTelemetry("svc-partial-timeout", 80L, EventStatus.OK);
            publishTelemetry("svc-partial-timeout", 800L, EventStatus.TIMEOUT);
            publishTelemetry("svc-partial-timeout", 900L, EventStatus.TIMEOUT);
            awaitTelemetryCount("svc-partial-timeout", 6);

            HealthMetrics body = graphApi.health("svc-partial-timeout", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(326L);   // (long)(1960/6.0) = 326
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isCloseTo(2.0 / 6.0, within(1e-9));
        }
    }

    // =========================================================================
    // Mixed Latency Distribution
    // =========================================================================

    @Nested
    @DisplayName("Mixed Latency Distribution")
    class MixedLatencyDistributionTests {

        /**
         * Deterministic scenario from the task specification.
         *
         * <pre>
         * latencies = [50, 100, 500, 800] sorted, statuses = [OK, OK, TIMEOUT, ERROR]
         * n = 4, count = ceil(3.8) = 4 → p95 = avg(1450) = 362 ms (1450/4 = 362.5 → (long) 362)
         * failureRate = 1 / 4 = 0.25
         * timeoutRate = 1 / 4 = 0.25
         * </pre>
         */
        @Test
        @DisplayName("mixed OK/TIMEOUT/ERROR events: exact p95, failure rate, and timeout rate")
        void mixedStatusDistributionComputesExactMetrics() {
            publishTelemetry("payment-service", 50L, EventStatus.OK);
            publishTelemetry("payment-service", 100L, EventStatus.OK);
            publishTelemetry("payment-service", 500L, EventStatus.TIMEOUT);
            publishTelemetry("payment-service", 800L, EventStatus.ERROR);
            awaitTelemetryCount("payment-service", 4);

            ResponseEntity<HealthMetrics> response = graphApi.health("payment-service", "5m");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            HealthMetrics body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getService()).isEqualTo("payment-service");
            assertThat(body.getP95LatencyMs()).isEqualTo(362L);
            assertThat(body.getFailureRate()).isEqualTo(0.25);
            assertThat(body.getTimeoutRate()).isEqualTo(0.25);
        }

        /**
         * p95 equals the arithmetic mean when all values are included (n ≤ 19).
         *
         * <p>This verifies the "average latency calculation" behaviour: for small
         * datasets the bottom-95% average is identical to the full-population mean.
         *
         * <pre>
         * latencies = [100, 200, 300] sorted
         * n = 3, count = ceil(2.85) = 3 → all included
         * p95 = avg(600) = 200 ms  ← equals arithmetic mean
         * </pre>
         */
        @Test
        @DisplayName("p95 equals arithmetic mean when n=3 (all values included in bottom-95% window)")
        void p95EqualsMeanForSmallDataset() {
            publishTelemetry("svc-avg", 100L, EventStatus.OK);
            publishTelemetry("svc-avg", 200L, EventStatus.OK);
            publishTelemetry("svc-avg", 300L, EventStatus.OK);
            awaitTelemetryCount("svc-avg", 3);

            HealthMetrics body = graphApi.health("svc-avg", "5m").getBody();

            assertThat(body).isNotNull();
            // p95 == avg(100, 200, 300) = 200 ms
            assertThat(body.getP95LatencyMs()).isEqualTo(200L);
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }

        /**
         * At n = 20 the p95 calculation excludes the single highest value.
         *
         * <pre>
         * events     : 19 × 100 ms (OK)  +  1 × 5000 ms (OK)
         * n = 20, count = (int) ceil(0.95 × 20) = ceil(19.0) = 19   → excludes top-1 (5000 ms)
         * p95 = avg(100 × 19) = 1900 / 19 = 100 ms
         * </pre>
         *
         * Without the exclusion (i.e. if using a simple mean) the result would be
         * avg(100×19 + 5000) / 20 = 6900/20 = 345 ms — confirming the outlier is dropped.
         */
        @Test
        @DisplayName("p95 suppresses single highest outlier at n=20: 5000 ms spike excluded")
        void p95ExcludesHighestOutlierAtN20() {
            for (int i = 0; i < 19; i++) {
                publishTelemetry("svc-p95", 100L, EventStatus.OK);
            }
            publishTelemetry("svc-p95", 5000L, EventStatus.OK);  // outlier — will be excluded
            awaitTelemetryCount("svc-p95", 20);

            HealthMetrics body = graphApi.health("svc-p95", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(100L);  // outlier excluded
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }

        /**
         * High failure and timeout rates signal an unhealthy service state.
         *
         * <pre>
         * events     : 1 OK + 2 ERROR + 2 TIMEOUT
         * failureRate = 2 / 5 = 0.4   (> typical 5 % threshold → unhealthy)
         * timeoutRate = 2 / 5 = 0.4
         * latencies = [100, 200, 300, 400, 500] sorted
         * n = 5, count = ceil(4.75) = 5 → p95 = avg(1500) = 300 ms
         * </pre>
         */
        @Test
        @DisplayName("high failure and timeout rates indicate unhealthy service state")
        void highFailureAndTimeoutRatesIndicateUnhealthyState() {
            publishTelemetry("svc-unhealthy", 100L, EventStatus.OK);
            publishTelemetry("svc-unhealthy", 200L, EventStatus.ERROR);
            publishTelemetry("svc-unhealthy", 300L, EventStatus.ERROR);
            publishTelemetry("svc-unhealthy", 400L, EventStatus.TIMEOUT);
            publishTelemetry("svc-unhealthy", 500L, EventStatus.TIMEOUT);
            awaitTelemetryCount("svc-unhealthy", 5);

            HealthMetrics body = graphApi.health("svc-unhealthy", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(300L);   // avg(100+200+300+400+500)/5 = 1500/5 = 300
            assertThat(body.getFailureRate()).isEqualTo(0.4);
            assertThat(body.getTimeoutRate()).isEqualTo(0.4);
            // Explicitly document what "unhealthy" means in terms of the returned fields
            assertThat(body.getFailureRate()).isGreaterThan(0.05);  // above 5 % alert threshold
            assertThat(body.getTimeoutRate()).isGreaterThan(0.05);
        }
    }

    // =========================================================================
    // Rolling Time Window Filtering
    // =========================================================================

    @Nested
    @DisplayName("Rolling Time Window Filtering")
    class RollingWindowFilteringTests {

        /**
         * An event injected 10 minutes in the past must not appear in a 5-minute
         * query window; a subsequent fresh event IS included.
         *
         * <pre>
         * stale record : timestamp = now − 10 m, latency = 9999 ms, status = ERROR
         * 5m query BEFORE fresh event → total=0 → p95=0, rates=0.0
         * fresh event  : latency = 200 ms, status = OK
         * 5m query AFTER fresh event  → total=1 → p95=200, rates=0.0
         * </pre>
         */
        @Test
        @DisplayName("stale event (10 min old) excluded from 5-minute window")
        void staleEventOutsideWindowIsExcluded() {
            // Inject stale telemetry directly with a back-dated timestamp
            telemetryStore.record("svc-stale",
                    new EventTelemetry(Instant.now().minus(Duration.ofMinutes(10)), 9999L, EventStatus.ERROR));

            // Immediately query 5m window — stale event must not appear
            HealthMetrics beforeFresh = graphApi.health("svc-stale", "5m").getBody();
            assertThat(beforeFresh).isNotNull();
            assertThat(beforeFresh.getP95LatencyMs()).isEqualTo(0L);
            assertThat(beforeFresh.getFailureRate()).isEqualTo(0.0);
            assertThat(beforeFresh.getTimeoutRate()).isEqualTo(0.0);

            // Publish a fresh heartbeat — stale entry stays in store but outside window
            publishTelemetry("svc-stale", 200L, EventStatus.OK);
            // Wait for fresh event to appear in the 5m window (size becomes 1)
            awaitTelemetryCount("svc-stale", 1);

            // Now only the fresh event is visible
            HealthMetrics afterFresh = graphApi.health("svc-stale", "5m").getBody();
            assertThat(afterFresh).isNotNull();
            assertThat(afterFresh.getP95LatencyMs()).isEqualTo(200L);
            assertThat(afterFresh.getFailureRate()).isEqualTo(0.0);
            assertThat(afterFresh.getTimeoutRate()).isEqualTo(0.0);
        }

        /**
         * An event injected 45 minutes ago is visible in a 1-hour window but
         * not in a 30-minute window.
         *
         * <pre>
         * old record : timestamp = now − 45 m, latency = 500 ms, status = OK
         * fresh event: latency = 100 ms, status = OK
         *
         * "30m" query: 1 event → p95 = 100 ms
         * "1h"  query: 2 events → p95 = avg(100+500) = 300 ms
         * </pre>
         */
        @Test
        @DisplayName("1-hour window captures event invisible to 30-minute window")
        void longerWindowCapturesEventsMissedByShorterWindow() {
            // Direct injection with a 45-minute-old timestamp
            telemetryStore.record("svc-hour",
                    new EventTelemetry(Instant.now().minus(Duration.ofMinutes(45)), 500L, EventStatus.OK));

            // Publish fresh event
            publishTelemetry("svc-hour", 100L, EventStatus.OK);

            // Wait until the 1-hour window sees both events
            awaitAsserted(
                    () -> assertThat(telemetryStore.getWindow("svc-hour", Duration.ofHours(1)).size())
                            .isEqualTo(2),
                    Duration.ofSeconds(5));

            // 30-minute window: only the fresh event (p95 = 100 ms)
            HealthMetrics in30m = graphApi.health("svc-hour", "30m").getBody();
            assertThat(in30m).isNotNull();
            assertThat(in30m.getP95LatencyMs()).isEqualTo(100L);

            // 1-hour window: both events — n=2, count=2, p95 = avg(100+500) = 300 ms
            HealthMetrics in1h = graphApi.health("svc-hour", "1h").getBody();
            assertThat(in1h).isNotNull();
            assertThat(in1h.getP95LatencyMs()).isEqualTo(300L);
        }
    }

    // =========================================================================
    // Empty Telemetry Window
    // =========================================================================

    @Nested
    @DisplayName("Empty Telemetry Window")
    class EmptyTelemetryWindowTests {

        /**
         * Querying a service that has never published any telemetry returns
         * an all-zeros {@link HealthMetrics} (not a 404).
         */
        @Test
        @DisplayName("unknown service with no telemetry: all metrics are zero")
        void unknownServiceWithNoTelemetryReturnsZeros() {
            ResponseEntity<HealthMetrics> response =
                    graphApi.health("svc-never-published", "5m");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            HealthMetrics body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getService()).isEqualTo("svc-never-published");
            assertThat(body.getP95LatencyMs()).isEqualTo(0L);
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }

        /**
         * A stale event (outside the queried window) produces the same all-zeros
         * result as a completely empty store: the event exists in the store but
         * falls before the rolling window cutoff.
         */
        @Test
        @DisplayName("event older than query window: store has data but window returns zeros")
        void eventOlderThanWindowProducesZeroMetrics() {
            // Direct inject only — no fresh events published via the API
            telemetryStore.record("svc-stale-only",
                    new EventTelemetry(Instant.now().minus(Duration.ofMinutes(10)), 9999L, EventStatus.ERROR));

            HealthMetrics body = graphApi.health("svc-stale-only", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(0L);
            assertThat(body.getFailureRate()).isEqualTo(0.0);
            assertThat(body.getTimeoutRate()).isEqualTo(0.0);
        }

        /**
         * After the telemetry store is reset (simulated by the {@code @BeforeEach} in
         * {@link BaseE2ETest}), the same service name that held data in a prior test
         * produces zeros because the store was cleared.
         *
         * <p>This test explicitly verifies the reset contract rather than relying on
         * ordering or side-effects from another test method.
         */
        @Test
        @DisplayName("service previously seen returns zeros after telemetry store is cleared")
        void resetStoreClearsPreviousTelemetry() {
            // Directly inject telemetry then immediately clear (mimicking reset)
            telemetryStore.record("svc-reset-check",
                    new EventTelemetry(Instant.now(), 500L, EventStatus.ERROR));
            telemetryStore.clear();

            HealthMetrics body = graphApi.health("svc-reset-check", "5m").getBody();

            assertThat(body).isNotNull();
            assertThat(body.getP95LatencyMs()).isEqualTo(0L);
            assertThat(body.getFailureRate()).isEqualTo(0.0);
        }
    }

    // =========================================================================
    // Request Validation
    // =========================================================================

    @Nested
    @DisplayName("Request Validation")
    class RequestValidationTests {

        /**
         * A non-numeric window string is rejected with 400 Bad Request.
         * Handled by {@code GlobalExceptionHandler} for {@code InvalidWindowFormatException}.
         */
        @Test
        @DisplayName("invalid window format (non-numeric string) returns 400")
        void invalidWindowFormatReturnsBadRequest() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/api/graph/health/svc-test?window=invalid", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        /**
         * A zero value for the window parameter is rejected (the regex requires
         * a positive integer; zero is explicitly disallowed in {@code parseWindow}).
         */
        @Test
        @DisplayName("zero window value (0m) returns 400")
        void zeroWindowValueReturnsBadRequest() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/api/graph/health/svc-test?window=0m", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        /**
         * A missing {@code window} query parameter returns 400 because it is
         * declared as {@code @RequestParam} (required by default in Spring MVC).
         */
        @Test
        @DisplayName("missing window parameter returns 400")
        void missingWindowParameterReturnsBadRequest() {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "/api/graph/health/svc-test", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
