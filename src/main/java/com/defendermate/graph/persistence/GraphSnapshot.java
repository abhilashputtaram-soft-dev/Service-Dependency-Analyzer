package com.defendermate.graph.persistence;

import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.EventTelemetry;
import com.defendermate.graph.model.ServiceMetadata;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serialization DTO that represents the full persisted state of the in-memory
 * service-dependency graph.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code capturedAt}       — wall-clock time when the snapshot was written</li>
 *   <li>{@code edges}            — flat list of all directed dependency edges</li>
 *   <li>{@code metadata}         — service name → {@link ServiceMetadata}</li>
 *   <li>{@code telemetry}        — service name → ordered list of {@link EventTelemetry} records</li>
 *   <li>{@code processedEventIds}— set of already-seen event IDs, used to restore the
 *       deduplication barrier in {@link com.defendermate.graph.ingestion.EventProcessor}</li>
 * </ul>
 *
 * <p>Edges are stored as a flat list rather than repeating the nested adjacency-map
 * structure; both the outgoing and incoming maps can be fully reconstructed by
 * calling {@link com.defendermate.graph.graph.GraphStore#putEdge(Edge)} for each entry.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphSnapshot {

    /** Wall-clock timestamp at which the snapshot was captured. */
    private Instant capturedAt;

    /** All directed dependency edges in the graph at snapshot time. */
    private List<Edge> edges;

    /** All registered service metadata, keyed by service name. */
    private Map<String, ServiceMetadata> metadata;

    /**
     * Per-service telemetry records, keyed by service name.
     * Each list is ordered oldest → newest.
     */
    private Map<String, List<EventTelemetry>> telemetry;

    /**
     * Event IDs that have already been processed.
     * Restoring this set re-establishes the deduplication barrier so that
     * events redelivered after a restart are still discarded correctly.
     */
    private Set<String> processedEventIds;
}
