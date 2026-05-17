package com.defendermate.graph.telemetry;

import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.EventTelemetry;
import com.defendermate.graph.model.dto.HealthMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-memory store for per-service {@link EventTelemetry} records.
 *
 * <p>
 * Records are kept in insertion order (oldest → newest) inside a
 * {@link ConcurrentLinkedDeque} per service. Entries older than
 * {@code MAX_RETENTION} are evicted lazily whenever a new record is written,
 * keeping memory bounded without a background scheduler.
 *
 * <p>
 * Key operations:
 * <ul>
 * <li>{@link #record(String, EventTelemetry)} — append a telemetry entry</li>
 * <li>{@link #getWindow(String, Duration)} — raw entries within a time
 * window</li>
 * <li>{@link #getStats(String, Duration)} — aggregated stats for a window</li>
 * </ul>
 *
 * <p>
 * Example — "last 5 minutes for auth-service":
 * 
 * <pre>{@code
 * HealthMetrics stats = telemetryStore.getStats("auth-service", Duration.ofMinutes(5));
 * }</pre>
 */
@Slf4j
@Component
public class TelemetryStore {

    /** Maximum age of a record before it is eligible for eviction. */
    private static final Duration MAX_RETENTION = Duration.ofHours(1);

    /** service name → time-ordered telemetry (head = oldest, tail = newest). */
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<EventTelemetry>> store = new ConcurrentHashMap<>();

    /** Dedicated pool — one thread per metric (p95, failureRate, timeoutRate). */
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /**
     * Records a telemetry entry for {@code service} and evicts stale entries.
     *
     * @param service   the service being observed (source or target)
     * @param telemetry the telemetry data point to store
     */
    public void record(String service, EventTelemetry telemetry) {
        store.computeIfAbsent(service, k -> new ConcurrentLinkedDeque<>())
                .addLast(telemetry);
        evictExpired(service);
        log.debug("Recorded telemetry for {}: latency={}ms status={}", service, telemetry.getLatency(),
                telemetry.getStatus());
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    /**
     * Returns all raw {@link EventTelemetry} entries for {@code service} that
     * fall within the last {@code window}, in insertion order.
     *
     * @param service the service name to query
     * @param window  look-back duration (e.g. {@code Duration.ofMinutes(5)})
     * @return a (possibly empty) unmodifiable list of matching entries
     */
    public List<EventTelemetry> getWindow(String service, Duration window) {
        return windowEntries(service, window);
    }

    /**
     * Returns p95 latency, failure rate, and timeout rate for {@code service}
     * over the last {@code window}.
     *
     * <p>
     * Example — last 5 minutes for "auth-service":
     * 
     * <pre>{@code
     * HealthMetrics stats = telemetryStore.getStats("auth-service", Duration.ofMinutes(5));
     * }</pre>
     *
     * @param service the service name to query
     * @param window  look-back duration (e.g. {@code Duration.ofMinutes(5)})
     * @return {@link HealthMetrics} with zeros when no data exists for the window
     */
    public HealthMetrics getStats(String service, Duration window) {
        List<EventTelemetry> entries = windowEntries(service, window);
        int total = entries.size();

        if (total == 0)
            return new HealthMetrics(service, 0L, 0.0, 0.0);

        CompletableFuture<Long> p95CF = CompletableFuture.supplyAsync(
                () -> p95(entries.stream().map(EventTelemetry::getLatency).toList()), executor);

        CompletableFuture<Long> errorCF = CompletableFuture.supplyAsync(
                () -> entries.stream().filter(t -> t.getStatus() == EventStatus.ERROR).count(), executor);

        CompletableFuture<Long> timeoutCF = CompletableFuture.supplyAsync(
                () -> entries.stream().filter(t -> t.getStatus() == EventStatus.TIMEOUT).count(), executor);

        return CompletableFuture.allOf(p95CF, errorCF, timeoutCF)
                .thenApply(v -> new HealthMetrics(
                        service,
                        p95CF.join(),
                        (double) errorCF.join() / total,
                        (double) timeoutCF.join() / total))
                .join();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns entries for {@code service} whose timestamp is within the last
     * {@code window}.
     */
    private List<EventTelemetry> windowEntries(String service, Duration window) {
        ConcurrentLinkedDeque<EventTelemetry> deque = store.get(service);
        if (deque == null)
            return Collections.emptyList();
        Instant cutoff = Instant.now().minus(window);
        return deque.stream()
                .filter(t -> !t.getTimestamp().isBefore(cutoff))
                .toList();
    }

    /**
     * Average of the bottom 95 % of ascending-sorted latencies; returns 0 for an
     * empty list.
     */
    private long p95(List<Long> latencies) {
        if (latencies.isEmpty())
            return 0L;
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int count = (int) Math.ceil(0.95 * sorted.size());
        return (long) sorted.subList(0, count).stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
    }

    /**
     * Removes head entries older than {@link #MAX_RETENTION} for the given service.
     */
    private void evictExpired(String service) {
        Instant cutoff = Instant.now().minus(MAX_RETENTION);
        ConcurrentLinkedDeque<EventTelemetry> deque = store.get(service);
        if (deque == null)
            return;
        while (!deque.isEmpty()) {
            EventTelemetry head = deque.peekFirst();
            if (head != null && head.getTimestamp().isBefore(cutoff)) {
                deque.pollFirst();
            } else {
                break;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Persistence helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a point-in-time snapshot of all telemetry data across every service.
     *
     * <p>Each per-service deque is copied to an immutable list (oldest → newest).
     * The returned map is detached from the live store and safe to serialize without
     * holding any locks.
     *
     * @return map of service name → ordered list of telemetry records
     */
    public Map<String, List<EventTelemetry>> snapshotAll() {
        Map<String, List<EventTelemetry>> result = new HashMap<>();
        store.forEach((service, deque) -> result.put(service, List.copyOf(deque)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Bulk-loads telemetry records from a previously persisted snapshot.
     *
     * <p>Records are appended to any existing in-memory data for each service
     * (no prior state is cleared). Entries are inserted in the order they appear
     * in the supplied lists, which preserves the original oldest → newest ordering.
     *
     * @param data map of service name → ordered list of telemetry records to restore
     */
    public void restoreAll(Map<String, List<EventTelemetry>> data) {
        if (data == null) return;
        data.forEach((service, entries) -> {
            ConcurrentLinkedDeque<EventTelemetry> deque =
                    store.computeIfAbsent(service, k -> new ConcurrentLinkedDeque<>());
            entries.forEach(deque::addLast);
        });
    }

    /** Removes all telemetry records from every service. Used in tests to reset state. */
    public void clear() {
        store.clear();
    }
}
