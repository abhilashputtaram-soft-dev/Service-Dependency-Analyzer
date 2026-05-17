package com.defendermate.graph.persistence;

import com.defendermate.graph.graph.GraphStore;
import com.defendermate.graph.ingestion.EventProcessor;
import com.defendermate.graph.telemetry.TelemetryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages periodic and lifecycle-triggered persistence of the in-memory
 * service-dependency graph.
 *
 * <h3>Startup</h3>
 * <p>On {@link #start()}, if a snapshot file exists at the configured path,
 * it is deserialized and its contents are restored into {@link GraphStore},
 * {@link TelemetryStore}, and {@link EventProcessor} before the application
 * begins accepting traffic.  If no file exists the application starts fresh.
 *
 * <h3>Periodic saves</h3>
 * <p>A single-threaded background scheduler writes a new snapshot every
 * {@code snapshot.interval-seconds} seconds (configurable, default 60 s).
 * Setting the interval to {@code 0} disables periodic saves while keeping
 * the shutdown save active.
 *
 * <h3>Shutdown save</h3>
 * <p>On {@link #shutdown()}, a final snapshot is written synchronously before
 * the JVM exits, capturing any state accumulated after the last periodic save.
 *
 * <h3>Atomicity</h3>
 * <p>Each write is directed first to a {@code <path>.tmp} sibling file.
 * Once serialization is complete the temporary file is atomically renamed to
 * the target path ({@link StandardCopyOption#ATOMIC_MOVE}), so a crash or
 * power loss mid-write never corrupts the last good snapshot.
 *
 * <h3>Read-safety</h3>
 * <p>All state is read from the live {@link java.util.concurrent.ConcurrentHashMap}
 * structures via their snapshot helper methods, which return detached copies.
 * No application locks are held during serialization, ensuring the runtime
 * graph is not blocked or corrupted.
 */
@Slf4j
@Component
public class SnapshotManager {

    private final GraphStore graphStore;
    private final TelemetryStore telemetryStore;
    private final EventProcessor eventProcessor;
    private final SnapshotProperties properties;
    private final ObjectMapper objectMapper;

    /** Daemon scheduler for background periodic saves. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "snapshot-scheduler");
        t.setDaemon(true);
        return t;
    });

    public SnapshotManager(GraphStore graphStore,
                           TelemetryStore telemetryStore,
                           EventProcessor eventProcessor,
                           SnapshotProperties properties,
                           ObjectMapper objectMapper) {
        this.graphStore = graphStore;
        this.telemetryStore = telemetryStore;
        this.eventProcessor = eventProcessor;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Loads the snapshot from disk (if present) and starts the background scheduler.
     * Invoked automatically by Spring after dependency injection completes.
     */
    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("Snapshot persistence is disabled — skipping load and scheduler");
            return;
        }

        loadSnapshot();

        if (properties.getIntervalSeconds() > 0) {
            long interval = properties.getIntervalSeconds();
            scheduler.scheduleWithFixedDelay(this::saveSnapshot, interval, interval, TimeUnit.SECONDS);
            log.info("Snapshot scheduler started: interval={}s path={}", interval, properties.getPath());
        } else {
            log.info("Snapshot interval=0 — periodic saves disabled; shutdown save still active");
        }
    }

    /**
     * Writes a final snapshot and stops the background scheduler.
     * Invoked automatically by Spring during graceful shutdown.
     */
    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
        if (properties.isEnabled()) {
            log.info("Writing final snapshot on shutdown...");
            saveSnapshot();
        }
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Captures the current graph state and writes it atomically to the configured
     * snapshot path.
     *
     * <p>The write sequence is:
     * <ol>
     *   <li>Collect detached copies of all live state (edges, metadata, telemetry,
     *       processed event IDs) — no locks held.</li>
     *   <li>Serialize to a {@code .tmp} sibling file.</li>
     *   <li>Atomically rename the {@code .tmp} file to the target path.</li>
     * </ol>
     *
     * <p>Any I/O or serialization error is caught and logged; the method never
     * throws so that a scheduler or shutdown hook failure does not propagate.
     */
    public void saveSnapshot() {
        log.info("Snapshot save started path={}", properties.getPath());
        long startMs = System.currentTimeMillis();
        try {
            GraphSnapshot snapshot = new GraphSnapshot(
                    Instant.now(),
                    graphStore.getAllEdges(),
                    graphStore.getAllMetadata(),
                    telemetryStore.snapshotAll(),
                    eventProcessor.snapshotProcessedEventIds()
            );

            Path target = Path.of(properties.getPath());
            Path tmp    = Path.of(properties.getPath() + ".tmp");

            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            objectMapper.writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

            long durationMs = System.currentTimeMillis() - startMs;
            log.info("Snapshot save completed edges={} services={} eventIds={} durationMs={} path={}",
                    snapshot.getEdges().size(),
                    snapshot.getMetadata().size(),
                    snapshot.getProcessedEventIds().size(),
                    durationMs,
                    target);

        } catch (Exception e) {
            log.error("Failed to save graph snapshot to {}: {}", properties.getPath(), e.getMessage(), e);
        }
    }

    /**
     * Public entry point for restoring graph state from the configured snapshot file.
     *
     * <p>This delegates directly to {@link #loadSnapshot()} and is provided so that
     * test code — and any future operational tooling — can trigger a restore without
     * going through the {@link jakarta.annotation.PostConstruct} startup path.
     *
     * <p>Idempotent: calling this method when no snapshot file exists is a no-op.
     */
    public void restoreFromSnapshot() {
        loadSnapshot();
    }

    /**
     * Clears all in-memory state (graph, telemetry, processed-event IDs) and deletes
     * the snapshot file from disk. After this call the application is in the same state
     * as a fresh start with no snapshot present.
     *
     * <p>The method is thread-safe: each constituent clear operation is atomic on the
     * underlying {@link java.util.concurrent.ConcurrentHashMap}s. The background scheduler
     * continues running and will write a new (empty) snapshot at the next scheduled
     * interval unless persistence is disabled.
     */
    public void clearAll() {
        graphStore.clear();
        telemetryStore.clear();
        eventProcessor.clearProcessedEventIds();

        Path target = Path.of(properties.getPath());
        try {
            boolean deleted = Files.deleteIfExists(target);
            Path tmp = Path.of(properties.getPath() + ".tmp");
            Files.deleteIfExists(tmp);
            log.info("All in-memory data cleared; snapshot file deleted={} path={}", deleted, target);
        } catch (Exception e) {
            log.warn("In-memory data cleared but snapshot file deletion failed path={}: {}",
                    target, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Reads the persisted snapshot from disk and restores its contents into the
     * in-memory stores.
     *
     * <p>State is restored in dependency order:
     * <ol>
     *   <li>Edges — rebuilds both adjacency maps in {@link GraphStore}.</li>
     *   <li>Metadata — re-registers all service metadata.</li>
     *   <li>Telemetry — appends records back into {@link TelemetryStore}.</li>
     *   <li>Processed event IDs — re-establishes the deduplication barrier in
     *       {@link EventProcessor}.</li>
     * </ol>
     *
     * <p>If the snapshot file does not exist the method returns silently (fresh start).
     * Any parse or I/O error is caught and logged; the application continues with
     * whatever state was restored before the error.
     */
    private void loadSnapshot() {
        Path target = Path.of(properties.getPath());
        log.info("Snapshot load started path={}", target);
        if (!Files.exists(target)) {
            log.info("No snapshot file found at {} — starting with empty graph", target);
            return;
        }

        try {
            GraphSnapshot snapshot = objectMapper.readValue(target.toFile(), GraphSnapshot.class);

            if (snapshot.getEdges() != null) {
                snapshot.getEdges().forEach(graphStore::putEdge);
            }
            if (snapshot.getMetadata() != null) {
                snapshot.getMetadata().values().forEach(graphStore::putMetadata);
            }
            if (snapshot.getTelemetry() != null) {
                telemetryStore.restoreAll(snapshot.getTelemetry());
            }
            if (snapshot.getProcessedEventIds() != null) {
                eventProcessor.restoreProcessedEventIds(snapshot.getProcessedEventIds());
            }

            log.info("Snapshot loaded: edges={} services={} eventIds={} capturedAt={}",
                    snapshot.getEdges()            == null ? 0 : snapshot.getEdges().size(),
                    snapshot.getMetadata()         == null ? 0 : snapshot.getMetadata().size(),
                    snapshot.getProcessedEventIds()== null ? 0 : snapshot.getProcessedEventIds().size(),
                    snapshot.getCapturedAt());

        } catch (Exception e) {
            log.error("Failed to load graph snapshot from {}: {}", target, e.getMessage(), e);
        }
    }
}
