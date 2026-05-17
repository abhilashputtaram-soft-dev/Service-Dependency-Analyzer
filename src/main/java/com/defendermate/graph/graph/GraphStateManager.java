package com.defendermate.graph.graph;

import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.Event;
import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.ServiceMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Spring service responsible for safely applying graph mutations to {@link GraphStore}.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Dispatch incoming {@link Event}s to the correct graph mutation.</li>
 *   <li>Prevent duplicate processing via atomic {@code eventId} tracking.</li>
 *   <li>Keep running-average edge stats consistent under concurrent load using
 *       per-edge {@link ReentrantLock}s — the lock is acquired before
 *       read-compute-write so no two threads can race on the same edge.</li>
 *   <li>Delegate all structural graph writes to {@link GraphStore}, which uses
 *       {@code ConcurrentHashMap} for individual-operation thread safety.</li>
 * </ul>
 *
 * <h3>Thread safety model</h3>
 * <pre>
 *   DEPENDENCY_OBSERVED
 *     edgeLock(source,target).lock()
 *       graphStore.getEdge(source, target)   ← CHM read
 *       compute updated stats
 *       graphStore.putEdge(updated)          ← CHM write
 *     edgeLock.unlock()
 *
 *   DEPENDENCY_REMOVED
 *     edgeLock(source,target).lock()
 *       graphStore.removeEdge(source,target) ← CHM write
 *     edgeLock.unlock()
 *
 *   SERVICE_METADATA / HEARTBEAT
 *     direct graphStore call (single CHM operation, no lock needed)
 * </pre>
 *
 * <p>Both OBSERVED and REMOVED share the same per-edge lock. Without this,
 * a REMOVED arriving mid-way through an OBSERVED's read-compute-write cycle
 * would be silently overwritten by the subsequent {@code putEdge}.
 */
@Slf4j
@Service
public class GraphStateManager {

    private final GraphStore graphStore;

    /**
     * Per-edge locks keyed by {@code "source→target"}.
     * Serialises all mutations for the same {@code (source, target)} pair — both the
     * read-compute-write cycle in {@link #handleDependencyObserved} and the removal
     * in {@link #handleDependencyRemoved} — so a concurrent REMOVED cannot be
     * silently overwritten by an in-flight OBSERVED.
     */
    private final ConcurrentHashMap<String, ReentrantLock> edgeLocks = new ConcurrentHashMap<>();

    public GraphStateManager(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Processes an incoming event, applying the appropriate graph mutation.
     *
     * <p>Events with a duplicate {@code eventId} are silently dropped.
     * Events with a {@code null} eventId are processed without deduplication.
     *
     * @param event the event to process; must not be {@code null}
     */
    public void processEvent(Event event) {
        switch (event.getType()) {
            case DEPENDENCY_OBSERVED -> handleDependencyObserved(event);
            case DEPENDENCY_REMOVED  -> handleDependencyRemoved(event);
            case SERVICE_METADATA    -> handleServiceMetadata(event);
            case HEARTBEAT           -> handleHeartbeat(event);
        }
    }

    /**
     * Registers or updates service-level metadata (team, tier, region).
     * This is the preferred path for {@code SERVICE_METADATA} events where
     * the caller has already resolved the full {@link ServiceMetadata} object.
     *
     * @param metadata the metadata to store; must not be {@code null}
     */
    public void registerService(ServiceMetadata metadata) {
        graphStore.putMetadata(metadata);
        log.info("Service registered service={} team={} tier={} region={}",
                metadata.getService(), metadata.getTeam(), metadata.getTier(), metadata.getRegion());
    }

    /**
     * Removes a service node and every edge that starts or ends at it.
     *
     * @param service the service name to remove
     */
    public void removeService(String service) {
        graphStore.removeService(service);
        log.info("Service removed service={}", service);
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    /**
     * Handles a {@code DEPENDENCY_OBSERVED} event.
     *
     * <p>Acquires a per-edge lock before the read-compute-write cycle so that
     * concurrent events for the same {@code (source, target)} pair update the
     * running stats sequentially and no increment is lost.
     *
     * <p>Running average formula:
     * <pre>
     *   newAvg = (existingAvg * existingCount + newLatency) / (existingCount + 1)
     * </pre>
     */
    private void handleDependencyObserved(Event event) {
        String source = event.getSource();
        String target = event.getTarget();

        if (source == null || target == null) {
            log.warn("DEPENDENCY_OBSERVED event missing source/target — skipping. eventId={}", event.getEventId());
            return;
        }

        ReentrantLock lock = edgeLocks.computeIfAbsent(edgeKey(source, target), k -> new ReentrantLock());
        lock.lock();
        try {
            Edge existing = graphStore.getEdge(source, target).orElse(null);

            if (existing == null) {
                // First observation for this (source, target) pair
                Instant firstSeen = event.getTimestamp() != null ? event.getTimestamp() : Instant.now();
                Edge newEdge = new Edge(
                        source,
                        target,
                        latencyOrZero(event),
                        1L,
                        isError(event) ? 1L : 0L,
                        firstSeen
                );
                graphStore.putEdge(newEdge);
                log.debug("Edge added source={} target={} eventId={}",
                        source, target, event.getEventId());

            } else {
                // Subsequent observation: update running stats in place
                long newCount    = existing.getRequestCount() + 1;
                double newAvg    = (existing.getAvgLatencyMs() * existing.getRequestCount() + latencyOrZero(event)) / newCount;
                long newErrors   = existing.getErrorCount() + (isError(event) ? 1L : 0L);
                Instant lastSeen = event.getTimestamp() != null ? event.getTimestamp() : Instant.now();

                Edge updated = new Edge(source, target, newAvg, newCount, newErrors, lastSeen);
                graphStore.putEdge(updated);
                log.debug("Edge updated source={} target={} requestCount={} errorCount={} avgLatencyMs={} eventId={}",
                        source, target, newCount, newErrors, String.format("%.2f", newAvg), event.getEventId());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handles a {@code DEPENDENCY_REMOVED} event.
     *
     * <p>Acquires the same per-edge lock used by {@link #handleDependencyObserved}.
     * Without this, a REMOVED arriving while an OBSERVED is mid-way through its
     * read-compute-write cycle would be overwritten by the subsequent
     * {@code putEdge} — the edge would silently reappear after removal.
     */
    private void handleDependencyRemoved(Event event) {
        String source = event.getSource();
        String target = event.getTarget();

        if (source == null || target == null) {
            log.warn("DEPENDENCY_REMOVED event missing source/target — skipping. eventId={}", event.getEventId());
            return;
        }

        ReentrantLock lock = edgeLocks.computeIfAbsent(edgeKey(source, target), k -> new ReentrantLock());
        lock.lock();
        try {
            boolean removed = graphStore.removeEdge(source, target);
            if (removed) {
                log.debug("Edge removed source={} target={} eventId={}",
                        source, target, event.getEventId());
            } else {
                log.warn("Edge removal skipped — edge not found source={} target={} eventId={}",
                        source, target, event.getEventId());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handles a {@code SERVICE_METADATA} event by registering the source service
     * as a known node in the graph.  Team, tier and region are not carried by
     * {@link Event}; callers needing full metadata should use
     * {@link #registerService(ServiceMetadata)} directly.
     */
    private void handleServiceMetadata(Event event) {
        if (event.getSource() == null) {
            log.warn("SERVICE_METADATA event missing source — skipping eventId={}", event.getEventId());
            return;
        }
        graphStore.putMetadata(new ServiceMetadata(event.getSource(), null, null, null));
        log.debug("Metadata updated service={} eventId={}", event.getSource(), event.getEventId());
    }

    /**
     * Handles a {@code HEARTBEAT} event, confirming the source service is alive.
     *
     * <p>Heartbeats carry no graph topology change; they are acknowledged in the log
     * so that gaps in the heartbeat stream are detectable by log-based monitoring.
     */
    private void handleHeartbeat(Event event) {
        if (event.getSource() == null) {
            log.warn("HEARTBEAT event missing source — skipping eventId={}", event.getEventId());
            return;
        }
        log.debug("Heartbeat received source={} eventId={}", event.getSource(), event.getEventId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the event latency as a {@code double}, or {@code 0.0} if absent.
     */
    private double latencyOrZero(Event event) {
        return event.getLatencyMs() != null ? (double) event.getLatencyMs() : 0.0;
    }

    /**
     * Returns {@code true} if the event's status represents an error.
     * {@link EventStatus#TIMEOUT} is tracked separately at the telemetry layer
     * and is not counted as a graph-level error here.
     */
    private boolean isError(Event event) {
        return event.getStatus() == EventStatus.ERROR;
    }

    /**
     * Composite key used to identify a directed edge in the per-edge lock map.
     */
    private String edgeKey(String source, String target) {
        return source + "\u2192" + target;
    }
}
