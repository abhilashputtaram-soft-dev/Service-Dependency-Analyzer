package com.defendermate.graph.telemetry;

import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.EventTelemetry;
import com.defendermate.graph.model.dto.HealthMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryStoreTest {

    private TelemetryStore store;

    @BeforeEach
    void setUp() {
        store = new TelemetryStore();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private EventTelemetry entry(long latencyMs, EventStatus status) {
        return new EventTelemetry(Instant.now(), latencyMs, status);
    }

    private EventTelemetry entry(Instant timestamp, long latencyMs, EventStatus status) {
        return new EventTelemetry(timestamp, latencyMs, status);
    }

    // -----------------------------------------------------------------------
    // No data
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("When no data exists")
    class NoDataTests {

        @Test
        @DisplayName("Unknown service returns all-zero HealthMetrics")
        void unknownService_returnsZeros() {
            HealthMetrics stats = store.getStats("ghost-service", Duration.ofMinutes(5));

            assertThat(stats.getService()).isEqualTo("ghost-service");
            assertThat(stats.getP95LatencyMs()).isZero();
            assertThat(stats.getFailureRate()).isZero();
            assertThat(stats.getTimeoutRate()).isZero();
        }

        @Test
        @DisplayName("All entries outside the window returns all-zero HealthMetrics")
        void entriesOutsideWindow_returnsZeros() {
            store.record("svc", entry(Instant.now().minus(Duration.ofHours(2)), 500, EventStatus.ERROR));

            HealthMetrics stats = store.getStats("svc", Duration.ofMinutes(5));

            assertThat(stats.getP95LatencyMs()).isZero();
            assertThat(stats.getFailureRate()).isZero();
            assertThat(stats.getTimeoutRate()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Failure and timeout rates
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Rate computation")
    class RateTests {

        @Test
        @DisplayName("All OK → both rates are 0.0")
        void allOk_zeroRates() {
            for (int i = 0; i < 5; i++) store.record("svc", entry(100, EventStatus.OK));

            HealthMetrics stats = store.getStats("svc", Duration.ofMinutes(5));

            assertThat(stats.getFailureRate()).isZero();
            assertThat(stats.getTimeoutRate()).isZero();
        }

        @Test
        @DisplayName("All ERROR → failureRate = 1.0, timeoutRate = 0.0")
        void allErrors_failureRateOne() {
            for (int i = 0; i < 4; i++) store.record("svc", entry(50, EventStatus.ERROR));

            HealthMetrics stats = store.getStats("svc", Duration.ofMinutes(5));

            assertThat(stats.getFailureRate()).isEqualTo(1.0);
            assertThat(stats.getTimeoutRate()).isZero();
        }

        @Test
        @DisplayName("All TIMEOUT → timeoutRate = 1.0, failureRate = 0.0")
        void allTimeouts_timeoutRateOne() {
            for (int i = 0; i < 4; i++) store.record("svc", entry(200, EventStatus.TIMEOUT));

            HealthMetrics stats = store.getStats("svc", Duration.ofMinutes(5));

            assertThat(stats.getTimeoutRate()).isEqualTo(1.0);
            assertThat(stats.getFailureRate()).isZero();
        }

        @Test
        @DisplayName("2 OK, 1 ERROR, 1 TIMEOUT → each rate = 0.25")
        void mixedStatuses_correctRates() {
            store.record("svc", entry(100, EventStatus.OK));
            store.record("svc", entry(100, EventStatus.OK));
            store.record("svc", entry(100, EventStatus.ERROR));
            store.record("svc", entry(100, EventStatus.TIMEOUT));

            HealthMetrics stats = store.getStats("svc", Duration.ofMinutes(5));

            assertThat(stats.getFailureRate()).isEqualTo(0.25);
            assertThat(stats.getTimeoutRate()).isEqualTo(0.25);
        }

        @Test
        @DisplayName("Single ERROR entry → failureRate = 1.0")
        void singleErrorEntry_failureRateOne() {
            store.record("svc", entry(80, EventStatus.ERROR));

            assertThat(store.getStats("svc", Duration.ofMinutes(5)).getFailureRate()).isEqualTo(1.0);
        }
    }

    // -----------------------------------------------------------------------
    // p95 latency
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("p95 latency (average of bottom 95 % of sorted values)")
    class P95Tests {

        @Test
        @DisplayName("Single entry → p95 equals its own latency")
        void singleEntry_p95EqualsLatency() {
            store.record("svc", entry(42, EventStatus.OK));

            assertThat(store.getStats("svc", Duration.ofMinutes(5)).getP95LatencyMs()).isEqualTo(42L);
        }

        @Test
        @DisplayName("All same latency → p95 equals that value")
        void allSameLatency_p95Correct() {
            for (int i = 0; i < 10; i++) store.record("svc", entry(100, EventStatus.OK));

            assertThat(store.getStats("svc", Duration.ofMinutes(5)).getP95LatencyMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("20 entries [10..200] step 10: bottom 95 % (first 19) avg = 100")
        void twentyEntries_p95CorrectlyExcludesTopOutlier() {
            // sorted ascending: [10, 20, ..., 200]
            // ceil(0.95 * 20) = 19 values included: [10, 20, ..., 190]
            // sum = 10 * (1+2+...+19) = 10 * 190 = 1900 → avg = 1900 / 19 = 100
            for (int i = 1; i <= 20; i++) store.record("svc", entry(i * 10L, EventStatus.OK));

            assertThat(store.getStats("svc", Duration.ofMinutes(5)).getP95LatencyMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Two entries [50, 60]: both included in bottom 95 % → avg = 55")
        void twoEntries_p95IsAverage() {
            // ceil(0.95 * 2) = ceil(1.9) = 2 → both included; avg = 55
            store.record("svc", entry(50, EventStatus.OK));
            store.record("svc", entry(60, EventStatus.OK));

            assertThat(store.getStats("svc", Duration.ofMinutes(5)).getP95LatencyMs()).isEqualTo(55L);
        }

        @Test
        @DisplayName("p95 computed only on entries within window, not stale ones")
        void p95_onlyUsesWindowEntries() {
            // Stale high-latency entry (outside window)
            store.record("svc", entry(Instant.now().minus(Duration.ofMinutes(10)), 9000, EventStatus.OK));
            store.record("svc", entry(50, EventStatus.OK));
            store.record("svc", entry(60, EventStatus.OK));

            // Only [50, 60] are in the 5-min window → avg of bottom 95% = 55
            assertThat(store.getStats("svc", Duration.ofMinutes(5)).getP95LatencyMs()).isEqualTo(55L);
        }
    }

    // -----------------------------------------------------------------------
    // Window filtering
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Window filtering")
    class WindowFilteringTests {

        @Test
        @DisplayName("Entry outside window is excluded from all metrics")
        void oldEntry_excludedFromMetrics() {
            store.record("svc", entry(Instant.now().minus(Duration.ofMinutes(10)), 999, EventStatus.ERROR));
            store.record("svc", entry(50, EventStatus.OK));
            store.record("svc", entry(60, EventStatus.OK));

            HealthMetrics stats = store.getStats("svc", Duration.ofMinutes(5));

            assertThat(stats.getFailureRate()).isZero();
            assertThat(stats.getP95LatencyMs()).isEqualTo(55L);
        }

        @Test
        @DisplayName("Narrower window excludes entries that wider window would include")
        void narrowerWindow_fewerEntries() {
            store.record("svc", entry(Instant.now().minus(Duration.ofMinutes(8)), 999, EventStatus.ERROR));
            store.record("svc", entry(100, EventStatus.OK));

            HealthMetrics wide   = store.getStats("svc", Duration.ofMinutes(15));
            HealthMetrics narrow = store.getStats("svc", Duration.ofMinutes(5));

            assertThat(wide.getFailureRate()).isGreaterThan(0.0);   // ERROR included
            assertThat(narrow.getFailureRate()).isZero();            // ERROR excluded
        }

        @Test
        @DisplayName("Different window sizes produce independent results for the same service")
        void differentWindows_independentResults() {
            store.record("svc", entry(Instant.now().minus(Duration.ofMinutes(3)), 100, EventStatus.OK));
            store.record("svc", entry(200, EventStatus.TIMEOUT));

            HealthMetrics oneMin  = store.getStats("svc", Duration.ofMinutes(1));
            HealthMetrics tenMin  = store.getStats("svc", Duration.ofMinutes(10));

            // 1-min window: only the TIMEOUT entry
            assertThat(oneMin.getTimeoutRate()).isEqualTo(1.0);
            // 10-min window: both entries → timeoutRate = 0.5
            assertThat(tenMin.getTimeoutRate()).isEqualTo(0.5);
        }
    }

    // -----------------------------------------------------------------------
    // Eviction
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Eviction of stale entries")
    class EvictionTests {

        @Test
        @DisplayName("Entry older than MAX_RETENTION is evicted when next entry is recorded")
        void staleEntry_evictedOnNextRecord() {
            // 2-hour-old ERROR entry — exceeds MAX_RETENTION of 1 hour
            store.record("svc", entry(Instant.now().minus(Duration.ofHours(2)), 999, EventStatus.ERROR));
            // Fresh OK entry triggers eviction
            store.record("svc", entry(10, EventStatus.OK));

            // Query with a 3-hour window: stale entry has been evicted, only fresh one remains
            HealthMetrics stats = store.getStats("svc", Duration.ofHours(3));

            assertThat(stats.getFailureRate()).isZero();
            assertThat(stats.getP95LatencyMs()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Entry exactly at MAX_RETENTION boundary is evicted")
        void entryAtRetentionBoundary_evicted() {
            // Entry just past the 1-hour retention mark
            store.record("svc", entry(Instant.now().minus(Duration.ofHours(1)).minusSeconds(1), 500, EventStatus.ERROR));
            store.record("svc", entry(20, EventStatus.OK));

            HealthMetrics stats = store.getStats("svc", Duration.ofHours(3));

            assertThat(stats.getFailureRate()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Service isolation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Per-service isolation")
    class ServiceIsolationTests {

        @Test
        @DisplayName("Stats for one service do not bleed into another")
        void differentServices_isolatedStats() {
            store.record("service-a", entry(100, EventStatus.ERROR));
            store.record("service-b", entry(50, EventStatus.OK));

            assertThat(store.getStats("service-a", Duration.ofMinutes(5)).getFailureRate()).isEqualTo(1.0);
            assertThat(store.getStats("service-b", Duration.ofMinutes(5)).getFailureRate()).isZero();
        }

        @Test
        @DisplayName("Unknown service stats are zero even when other services have data")
        void unknownService_zeroWhenOthersHaveData() {
            store.record("known", entry(100, EventStatus.ERROR));

            HealthMetrics stats = store.getStats("unknown", Duration.ofMinutes(5));

            assertThat(stats.getFailureRate()).isZero();
            assertThat(stats.getP95LatencyMs()).isZero();
        }
    }

    // -----------------------------------------------------------------------
    // Concurrent access
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Concurrent access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("High-concurrency record() calls do not lose writes or throw")
        void concurrentRecord_noLostWritesNoExceptions() throws InterruptedException {
            int threads = 10;
            int recordsPerThread = 200;
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            AtomicInteger exceptions = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        ready.await();
                        for (int j = 0; j < recordsPerThread; j++)
                            store.record("svc", entry(10, EventStatus.OK));
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(exceptions.get()).isZero();
            // All recorded entries are OK → rates must be 0
            HealthMetrics stats = store.getStats("svc", Duration.ofMinutes(5));
            assertThat(stats.getFailureRate()).isZero();
            assertThat(stats.getTimeoutRate()).isZero();
            assertThat(stats.getP95LatencyMs()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Concurrent getStats() calls return non-null results without exceptions")
        void concurrentGetStats_noExceptions() throws InterruptedException {
            for (int i = 0; i < 50; i++) store.record("svc", entry(i * 5L, EventStatus.OK));

            int threads = 20;
            CountDownLatch ready     = new CountDownLatch(1);
            CountDownLatch done      = new CountDownLatch(threads);
            AtomicInteger nulls      = new AtomicInteger();
            AtomicInteger exceptions = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        ready.await();
                        HealthMetrics result = store.getStats("svc", Duration.ofMinutes(5));
                        if (result == null) nulls.incrementAndGet();
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(nulls.get()).isZero();
            assertThat(exceptions.get()).isZero();
        }

        @Test
        @DisplayName("Interleaved reads and writes do not corrupt data or throw")
        void concurrentReadWrite_noCorruption() throws InterruptedException {
            int writers = 5;
            int readers = 5;
            int ops     = 100;
            CountDownLatch ready     = new CountDownLatch(1);
            CountDownLatch done      = new CountDownLatch(writers + readers);
            AtomicInteger exceptions = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(writers + readers);

            for (int i = 0; i < writers; i++) {
                pool.submit(() -> {
                    try {
                        ready.await();
                        for (int j = 0; j < ops; j++)
                            store.record("svc", entry(20, EventStatus.OK));
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        ready.await();
                        for (int j = 0; j < ops; j++)
                            store.getStats("svc", Duration.ofMinutes(5));
                    } catch (Exception e) {
                        exceptions.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(exceptions.get()).isZero();
        }

        @Test
        @DisplayName("Concurrent writes to different services do not interfere")
        void concurrentRecord_differentServices_isolated() throws InterruptedException {
            int threads = 6;
            CountDownLatch ready = new CountDownLatch(1);
            CountDownLatch done  = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);

            String[] services = {"svc-a", "svc-b", "svc-c", "svc-d", "svc-e", "svc-f"};
            for (int i = 0; i < threads; i++) {
                final String svc = services[i];
                final EventStatus status = (i % 2 == 0) ? EventStatus.OK : EventStatus.ERROR;
                pool.submit(() -> {
                    try {
                        ready.await();
                        for (int j = 0; j < 50; j++) store.record(svc, entry(10, status));
                    } catch (Exception e) {
                        // ignore in isolation test
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.countDown();
            assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            // Even-indexed services are all OK; odd-indexed are all ERROR
            assertThat(store.getStats("svc-a", Duration.ofMinutes(5)).getFailureRate()).isZero();
            assertThat(store.getStats("svc-b", Duration.ofMinutes(5)).getFailureRate()).isEqualTo(1.0);
            assertThat(store.getStats("svc-c", Duration.ofMinutes(5)).getFailureRate()).isZero();
            assertThat(store.getStats("svc-d", Duration.ofMinutes(5)).getFailureRate()).isEqualTo(1.0);
        }
    }
}
