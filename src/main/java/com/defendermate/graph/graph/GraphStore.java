package com.defendermate.graph.graph;

import com.defendermate.graph.model.Edge;
import com.defendermate.graph.model.ServiceMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for the directed service-dependency graph.
 *
 * <p>Internal structure:
 * <ul>
 *   <li>{@code outgoingEdges}: source → Map&lt;target, Edge&gt; — one entry per source, keyed by target</li>
 *   <li>{@code incomingEdges}: target → Map&lt;source, Edge&gt; — one entry per target, keyed by source</li>
 *   <li>{@code metadataStore}: service → ServiceMetadata</li>
 * </ul>
 *
 * <p>Thread safety is provided entirely by {@link ConcurrentHashMap} at both the outer
 * and inner map levels.  No additional locking is needed: each individual CHM operation
 * is atomic, and business-level consistency (read-compute-write sequences) is the
 * responsibility of {@code GraphStateManager}, not this store.
 */
@Slf4j
@Component
public class GraphStore {

    // outgoingEdges: source → (target → Edge)  — one entry per source service, inner key is target
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Edge>> outgoingEdges = new ConcurrentHashMap<>();

    // incomingEdges: target → (source → Edge)  — one entry per target service, inner key is source
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Edge>> incomingEdges = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ServiceMetadata> metadataStore =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Mutation methods
    // -------------------------------------------------------------------------

    /**
     * Stores the edge (source → target) in both adjacency maps, replacing any existing entry.
     * Callers are responsible for computing the correct edge state before calling this method.
     */
    public void putEdge(Edge edge) {
        outgoingEdges.computeIfAbsent(edge.getSource(), k -> new ConcurrentHashMap<>())
                     .put(edge.getTarget(), edge);
        incomingEdges.computeIfAbsent(edge.getTarget(), k -> new ConcurrentHashMap<>())
                     .put(edge.getSource(), edge);
        log.debug("Edge stored: {} -> {}", edge.getSource(), edge.getTarget());
    }

    /**
     * Removes the edge (source → target) from both adjacency maps.
     *
     * @return {@code true} if the edge existed and was removed
     */
    public boolean removeEdge(String source, String target) {
        boolean removed = false;

        ConcurrentHashMap<String, Edge> outs = outgoingEdges.get(source);
        if (outs != null) {
            removed = outs.remove(target) != null;
            if (outs.isEmpty()) outgoingEdges.remove(source);
        }

        ConcurrentHashMap<String, Edge> ins = incomingEdges.get(target);
        if (ins != null) {
            ins.remove(source);
            if (ins.isEmpty()) incomingEdges.remove(target);
        }

        if (removed) log.debug("Edge removed: {} -> {}", source, target);
        return removed;
    }

    /**
     * Registers or updates the metadata for a service node.
     */
    public void putMetadata(ServiceMetadata metadata) {
        metadataStore.put(metadata.getService(), metadata);
        log.debug("Metadata stored for service: {}", metadata.getService());
    }

    /**
     * Removes a service node together with every edge that starts or ends at it.
     */
    public void removeService(String service) {
        metadataStore.remove(service);

        // Drop all outgoing edges from this service and clean up incomingEdges index
        ConcurrentHashMap<String, Edge> outs = outgoingEdges.remove(service);
        if (outs != null) {
            for (String target : outs.keySet()) {
                ConcurrentHashMap<String, Edge> ins = incomingEdges.get(target);
                if (ins != null) {
                    ins.remove(service);
                    if (ins.isEmpty()) incomingEdges.remove(target);
                }
            }
        }

        // Drop all incoming edges to this service and clean up outgoingEdges index
        ConcurrentHashMap<String, Edge> ins = incomingEdges.remove(service);
        if (ins != null) {
            for (String source : ins.keySet()) {
                ConcurrentHashMap<String, Edge> outsOfSource = outgoingEdges.get(source);
                if (outsOfSource != null) {
                    outsOfSource.remove(service);
                    if (outsOfSource.isEmpty()) outgoingEdges.remove(source);
                }
            }
        }

        log.debug("Service removed from graph: {}", service);
    }

    // -------------------------------------------------------------------------
    // Read / query methods
    // -------------------------------------------------------------------------

    /**
     * Returns the edge between source and target, if it exists.
     */
    public Optional<Edge> getEdge(String source, String target) {
        ConcurrentHashMap<String, Edge> outs = outgoingEdges.get(source);
        return outs == null ? Optional.empty() : Optional.ofNullable(outs.get(target));
    }

    /**
     * Returns an immutable snapshot of all edges originating from {@code source}.
     */
    public Set<Edge> getOutgoingEdges(String source) {
        ConcurrentHashMap<String, Edge> outs = outgoingEdges.get(source);
        return outs == null ? Set.of() : Set.copyOf(outs.values());
    }

    /**
     * Returns an immutable snapshot of all edges pointing to {@code target}.
     */
    public Set<Edge> getIncomingEdges(String target) {
        ConcurrentHashMap<String, Edge> ins = incomingEdges.get(target);
        return ins == null ? Set.of() : Set.copyOf(ins.values());
    }

    /**
     * Returns an immutable snapshot of all direct downstream service names for {@code source}.
     */
    public Set<String> getOutgoingNeighbors(String source) {
        ConcurrentHashMap<String, Edge> outs = outgoingEdges.get(source);
        return outs == null ? Set.of() : Set.copyOf(outs.keySet());
    }

    /**
     * Returns an immutable snapshot of all direct upstream service names that depend on {@code target}.
     */
    public Set<String> getIncomingNeighbors(String target) {
        ConcurrentHashMap<String, Edge> ins = incomingEdges.get(target);
        return ins == null ? Set.of() : Set.copyOf(ins.keySet());
    }

    /**
     * Returns the metadata for a service, if present.
     */
    public Optional<ServiceMetadata> getMetadata(String service) {
        return Optional.ofNullable(metadataStore.get(service));
    }

    /**
     * Returns an immutable snapshot of all registered service names.
     */
    public Set<String> getAllServices() {
        return Set.copyOf(metadataStore.keySet());
    }

    /**
     * Returns an immutable snapshot of all service names that appear as the
     * <em>target</em> of at least one active edge — i.e., every service that
     * currently has at least one known upstream dependent.
     *
     * <p>This set is derived purely from the live edge topology and is independent
     * of the metadata store, so it includes services that have received dependencies
     * but have not (yet) sent a {@code SERVICE_METADATA} event.
     */
    public Set<String> getAllEdgeTargets() {
        return Set.copyOf(incomingEdges.keySet());
    }

    /**
     * Returns {@code true} if the service has been registered via {@link #putMetadata}.
     */
    public boolean containsService(String service) {
        return metadataStore.containsKey(service);
    }

    /**
     * Removes all edges and metadata from the store, resetting it to an empty state.
     */
    public void clear() {
        outgoingEdges.clear();
        incomingEdges.clear();
        metadataStore.clear();
        log.info("GraphStore cleared");
    }

    /**
     * Total number of directed edges currently stored.
     */
    public int edgeCount() {
        return outgoingEdges.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Total number of registered service nodes.
     */
    public int serviceCount() {
        return metadataStore.size();
    }

    // -------------------------------------------------------------------------
    // Persistence helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a flat, point-in-time snapshot of every edge in the graph.
     *
     * <p>Only the outgoing adjacency map is iterated; the incoming map is redundant
     * and can be fully rebuilt by calling {@link #putEdge(Edge)} for each returned edge.
     * The returned list is a detached copy and is safe to serialize without holding
     * any locks.
     *
     * @return immutable list of all current edges
     */
    public List<Edge> getAllEdges() {
        return outgoingEdges.values().stream()
                .flatMap(inner -> inner.values().stream())
                .toList();
    }

    /**
     * Returns a point-in-time snapshot of the full metadata store.
     *
     * <p>The returned map is an immutable detached copy and is safe to serialize
     * without holding any locks.
     *
     * @return immutable map of service name → {@link ServiceMetadata}
     */
    public Map<String, ServiceMetadata> getAllMetadata() {
        return Map.copyOf(metadataStore);
    }
}
