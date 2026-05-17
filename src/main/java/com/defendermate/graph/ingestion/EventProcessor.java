package com.defendermate.graph.ingestion;

import com.defendermate.graph.graph.GraphStateManager;
import com.defendermate.graph.model.Event;
import com.defendermate.graph.model.EventTelemetry;
import com.defendermate.graph.model.EventType;
import com.defendermate.graph.telemetry.TelemetryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes a single {@link Event} dequeued by an {@link EventConsumer} thread.
 *
 * <h3>Processing pipeline per event</h3>
 * <ol>
 *   <li>Delegate to {@link GraphStateManager#processEvent(Event)} — applies the graph
 *       topology mutation and performs deduplication by {@code eventId}.</li>
 *   <li>If the event carries latency and status data, append a {@link EventTelemetry}
 *       record to {@link TelemetryStore} for the source service.</li>
 * </ol>
 *
 * <h3>Fault isolation</h3>
 * <p>All processing is wrapped in a try/catch so that a single malformed or
 * unexpected event does not crash the consumer thread or stall the pipeline.
 * Failures are logged at WARN level with the offending {@code eventId}.
 *
 * <h3>Deduplication</h3>
 * <p>Deduplication is handled inside {@link GraphStateManager}; this class does
 * not need a separate seen-set.
 */
@Slf4j
@Component
public class EventProcessor {

    /**
     * Tracks processed event IDs to discard duplicates across the entire pipeline.
     * Key = eventId, value = time first processed.
     *
     * <p>Note: entries are never evicted in this in-memory implementation.
     * A production system would add TTL-based eviction bounded by the upstream
     * deduplication window.
     */
    private final ConcurrentHashMap<String, Instant> processedEvents = new ConcurrentHashMap<>();

    private final GraphStateManager graphStateManager;
    private final TelemetryStore telemetryStore;

    public EventProcessor(GraphStateManager graphStateManager, TelemetryStore telemetryStore) {
        this.graphStateManager = graphStateManager;
        this.telemetryStore = telemetryStore;
    }

    /**
     * Processes one event: updates graph topology and, when applicable, records
     * a telemetry entry.
     *
     * <p>Duplicate events (same {@code eventId} seen before) are skipped with a
     * WARN log. Events with a {@code null} type are rejected as invalid.
     * Any exception thrown by downstream components is caught and logged so
     * the calling consumer thread continues processing subsequent events.
     *
     * @param event the event to process; must not be {@code null}
     */
    public void process(Event event) {
        if (event.getType() == null) {
            log.warn("Invalid event skipped — missing type eventId={} source={}",
                    event.getEventId(), event.getSource());
            return;
        }

        if (event.getEventId() != null && !markProcessed(event.getEventId())) {
            log.warn("Duplicate event skipped eventId={} type={}",
                    event.getEventId(), event.getType());
            return;
        }

        long startMs = System.currentTimeMillis();
        try {
            // 1. Apply graph topology mutation
            graphStateManager.processEvent(event);

            // 2. Record telemetry for events that carry latency/status data
            if (shouldRecordTelemetry(event)) {
                telemetryStore.record(
                        event.getSource(),
                        new EventTelemetry(event.getTimestamp(), event.getLatencyMs(), event.getStatus())
                );
            }

            long processingTimeMs = System.currentTimeMillis() - startMs;
            log.debug("Event processed eventId={} type={} processingTimeMs={} thread={}",
                    event.getEventId(), event.getType(), processingTimeMs,
                    Thread.currentThread().getName());

        } catch (Exception e) {
            log.warn("Failed to process event eventId={} type={} source={}: {}",
                    event.getEventId(), event.getType(), event.getSource(), e.getMessage(), e);
        }
    }

    /**
     * Returns {@code true} when the event has enough data to populate a
     * meaningful telemetry record (source service, latency, and status all present).
     *
     * <p>Only {@code DEPENDENCY_OBSERVED} and {@code HEARTBEAT} events carry latency;
     * {@code DEPENDENCY_REMOVED} and {@code SERVICE_METADATA} do not.
     */
    /**
     * Atomically marks an eventId as processed.
     *
     * @return {@code true} if this is the first time the eventId was seen
     */
    private boolean markProcessed(String eventId) {
        return processedEvents.putIfAbsent(eventId, Instant.now()) == null;
    }

    // -----------------------------------------------------------------------
    // Persistence helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a point-in-time snapshot of all event IDs that have been processed.
     *
     * <p>Restoring this set after a restart re-establishes the deduplication barrier,
     * ensuring that events redelivered by the upstream producer are still discarded.
     *
     * @return immutable copy of all processed event IDs
     */
    public Set<String> snapshotProcessedEventIds() {
        return Set.copyOf(processedEvents.keySet());
    }

    /**
     * Bulk-restores processed event IDs from a previously persisted snapshot.
     *
     * <p>Each ID is inserted with the current wall-clock time as the "first seen"
     * timestamp. IDs that are already present in the local map are not overwritten.
     *
     * @param ids the set of event IDs to restore; {@code null} is treated as empty
     */
    public void restoreProcessedEventIds(Set<String> ids) {
        if (ids == null) return;
        Instant now = Instant.now();
        ids.forEach(id -> processedEvents.putIfAbsent(id, now));
    }

    /** Clears all processed event IDs. Used in tests to reset deduplication state. */
    public void clearProcessedEventIds() {
        processedEvents.clear();
    }

    private boolean shouldRecordTelemetry(Event event) {
        return event.getSource() != null
                && event.getLatencyMs() != null
                && event.getStatus() != null
                && (event.getType() == EventType.DEPENDENCY_OBSERVED
                        || event.getType() == EventType.HEARTBEAT);
    }
}
