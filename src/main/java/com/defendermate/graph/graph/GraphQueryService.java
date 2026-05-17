package com.defendermate.graph.graph;

import com.defendermate.graph.model.dto.CriticalService;
import com.defendermate.graph.model.dto.CriticalServicesResponse;
import com.defendermate.graph.model.dto.CycleEntry;
import com.defendermate.graph.model.dto.CyclesResponse;
import com.defendermate.graph.model.dto.DependentPath;
import com.defendermate.graph.model.dto.DependentsResponse;
import com.defendermate.graph.model.dto.ReachablePath;
import com.defendermate.graph.model.dto.ReachableResponse;
import com.defendermate.graph.model.dto.ShortestPathResponse;
import com.defendermate.graph.model.dto.HealthMetrics;
import com.defendermate.graph.model.Edge;
import com.defendermate.graph.telemetry.TelemetryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spring service that executes read-only analytical queries against the dependency graph.
 *
 * <p>All queries are non-mutating and delegate structural reads to {@link GraphStore}.
 * No locking is needed here: {@code GraphStore}'s {@code ConcurrentHashMap}-backed
 * reads are individually thread-safe, and the snapshot semantics of
 * {@link GraphStore#getOutgoingNeighbors} ensure a consistent view per traversal step.
 */
@Slf4j
@Service
public class GraphQueryService {

    private final GraphStore graphStore;
    private final TelemetryStore telemetryStore;

    public GraphQueryService(GraphStore graphStore, TelemetryStore telemetryStore) {
        this.graphStore = graphStore;
        this.telemetryStore = telemetryStore;
    }

    // -------------------------------------------------------------------------
    // Reachability
    // -------------------------------------------------------------------------

    /**
     * Returns every downstream service reachable from {@code sourceService} by
     * following outgoing dependency edges, together with the traversal path to
     * each one.
     *
     * <h3>Algorithm</h3>
     * <p>Depth-first search from {@code sourceService}.  A global {@code visited}
     * set (seeded with the source itself) guarantees:
     * <ul>
     *   <li>Each downstream service appears in {@code paths} exactly once —
     *       the DFS-first path to it.</li>
     *   <li>Cycles cannot cause infinite recursion: a node already in
     *       {@code visited} is never expanded again.</li>
     * </ul>
     *
     * <h3>Unknown service</h3>
     * <p>If {@code sourceService} has no outgoing edges (or does not exist in the
     * graph at all), the response contains an empty {@code paths} list and
     * {@code totalReachableServices = 0}.
     *
     * @param sourceService the service from which to start the traversal
     * @return a {@link ReachableResponse} with one {@link ReachablePath} per
     *         reachable downstream service
     */
    public ReachableResponse reachable(String sourceService) {
        List<ReachablePath> paths = new ArrayList<>();

        // visited tracks every node that has already been assigned a path,
        // including the source so it can never appear as a target of itself.
        Set<String> visited = new LinkedHashSet<>();
        visited.add(sourceService);

        dfs(sourceService, visited, new ArrayList<>(List.of(sourceService)), paths);

        log.debug("reachable({}) → {} services", sourceService, paths.size());
        return new ReachableResponse(sourceService, paths.size(), paths);
    }

    // -------------------------------------------------------------------------
    // Internal DFS helper
    // -------------------------------------------------------------------------

    /**
     * Recursively expands {@code current}'s outgoing neighbors via DFS.
     *
     * @param current     the node being expanded in this call
     * @param visited     globally-visited set; mutated in place as new nodes are discovered
     * @param currentPath the ordered path from the source to {@code current}, inclusive
     * @param results     accumulator for completed {@link ReachablePath} entries
     */
    private void dfs(String current,
                     Set<String> visited,
                     List<String> currentPath,
                     List<ReachablePath> results) {

        for (String neighbor : graphStore.getOutgoingNeighbors(current)) {
            if (visited.contains(neighbor)) {
                continue; // already reached via an earlier DFS branch — skip to prevent cycles
            }

            visited.add(neighbor);

            List<String> pathToNeighbor = new ArrayList<>(currentPath);
            pathToNeighbor.add(neighbor);

            results.add(new ReachablePath(neighbor, List.copyOf(pathToNeighbor)));

            dfs(neighbor, visited, pathToNeighbor, results);
        }
    }

    // -------------------------------------------------------------------------
    // Dependents (reverse reachability)
    // -------------------------------------------------------------------------

    /**
     * Returns every upstream service that directly or indirectly depends on
     * {@code targetService}, together with the traversal path from each upstream
     * service down to the target.
     *
     * <h3>Algorithm</h3>
     * <p>Reverse depth-first search starting at {@code targetService}, following
     * <em>incoming</em> dependency edges at each step.  A global {@code visited}
     * set (seeded with the target itself) guarantees:
     * <ul>
     *   <li>Each upstream service appears in {@code dependents} exactly once —
     *       the DFS-first route from it to the target.</li>
     *   <li>Cycles cannot cause infinite recursion: a node already in
     *       {@code visited} is never expanded again.</li>
     * </ul>
     *
     * <h3>Path direction</h3>
     * <p>Each {@link DependentPath#getPath()} list is ordered from the upstream
     * dependent service (first element) down to {@code targetService} (last element),
     * mirroring the natural direction of the dependency relationship.
     *
     * <h3>Unknown service</h3>
     * <p>If {@code targetService} has no incoming edges (or does not exist in the
     * graph at all), the response contains an empty {@code dependents} list and
     * {@code totalDependents = 0}.
     *
     * @param targetService the service for which to find all upstream dependents
     * @return a {@link DependentsResponse} with one {@link DependentPath} per
     *         reachable upstream dependent service
     */
    public DependentsResponse dependents(String targetService) {
        List<DependentPath> dependents = new ArrayList<>();

        // visited tracks every node already assigned an entry, including the target
        // so it can never appear as a dependent of itself.
        Set<String> visited = new LinkedHashSet<>();
        visited.add(targetService);

        reverseDfs(targetService, visited, new ArrayList<>(List.of(targetService)), dependents);

        log.debug("dependents({}) → {} services", targetService, dependents.size());
        return new DependentsResponse(targetService, dependents.size(), dependents);
    }

    /**
     * Recursively expands {@code current}'s incoming neighbors via reverse DFS.
     *
     * @param current     the node being expanded in this call
     * @param visited     globally-visited set; mutated in place as new nodes are discovered
     * @param currentPath the ordered path from the most-recently-found upstream node to
     *                    the target, inclusive; first element is the upstream node,
     *                    last element is always {@code targetService}
     * @param results     accumulator for completed {@link DependentPath} entries
     */
    private void reverseDfs(String current,
                            Set<String> visited,
                            List<String> currentPath,
                            List<DependentPath> results) {

        for (String upstream : graphStore.getIncomingNeighbors(current)) {
            if (visited.contains(upstream)) {
                continue; // already reached via an earlier branch — skip to prevent cycles
            }

            visited.add(upstream);

            // Prepend the new upstream node so the path reads: upstream → ... → target
            List<String> pathFromUpstream = new ArrayList<>();
            pathFromUpstream.add(upstream);
            pathFromUpstream.addAll(currentPath);

            results.add(new DependentPath(upstream, List.copyOf(pathFromUpstream)));

            reverseDfs(upstream, visited, pathFromUpstream, results);
        }
    }

    // -------------------------------------------------------------------------
    // Shortest path (Dijkstra)
    // -------------------------------------------------------------------------

    /**
     * Returns the minimum-latency path between {@code source} and {@code target}
     * by running Dijkstra's algorithm over outgoing dependency edges.
     *
     * <h3>Algorithm</h3>
     * <p>Standard Dijkstra with a min-heap priority queue.  Edge weights are
     * taken from {@link Edge#getAvgLatencyMs()}, which is always non-negative,
     * making Dijkstra correct and optimal.
     * <ul>
     *   <li>A {@code dist} map tracks the best known cumulative latency to every
     *       visited node, serving as the "settled" check that prevents stale
     *       priority-queue entries from re-expanding a node.</li>
     *   <li>A {@code prev} map records each node's predecessor on the current
     *       shortest path, enabling path reconstruction by back-tracking from
     *       {@code target} to {@code source}.</li>
     *   <li>The search terminates as soon as {@code target} is popped from the
     *       queue (its distance is then optimal).</li>
     * </ul>
     *
     * <h3>Self-path</h3>
     * <p>When {@code source} equals {@code target} the path is {@code [source]}
     * with {@code totalLatencyMs = 0.0} and {@code reachable = true}.
     *
     * <h3>Unreachable / unknown service</h3>
     * <p>If no path exists (disconnected graph, unknown node, etc.) the response
     * has an empty {@code path}, {@code totalLatencyMs = -1.0}, and
     * {@code reachable = false}.
     *
     * @param source the starting service
     * @param target the destination service
     * @return a {@link ShortestPathResponse} describing the minimum-latency route
     */
    public ShortestPathResponse shortestPath(String source, String target) {
        // dist[node] = best known cumulative latency from source
        Map<String, Double> dist = new HashMap<>();
        // prev[node] = predecessor on the shortest path to that node
        Map<String, String> prev = new HashMap<>();

        // Local record to bundle (cost, node) in the priority queue (Java 16+)
        record Entry(double cost, String node) {}
        PriorityQueue<Entry> pq = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));

        dist.put(source, 0.0);
        pq.offer(new Entry(0.0, source));

        while (!pq.isEmpty()) {
            Entry entry = pq.poll();
            double cost  = entry.cost();
            String current = entry.node();

            // Skip stale entries — a shorter path was already settled for this node
            if (cost > dist.getOrDefault(current, Double.MAX_VALUE)) {
                continue;
            }

            // Target reached with optimal cost — no need to explore further
            if (current.equals(target)) {
                break;
            }

            for (Edge edge : graphStore.getOutgoingEdges(current)) {
                String neighbor = edge.getTarget();
                double newCost  = cost + edge.getAvgLatencyMs();
                if (newCost < dist.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    dist.put(neighbor, newCost);
                    prev.put(neighbor, current);
                    pq.offer(new Entry(newCost, neighbor));
                }
            }
        }

        if (!dist.containsKey(target)) {
            log.debug("shortestPath({} → {}) → unreachable", source, target);
            return new ShortestPathResponse(source, target, List.of(), -1.0, false);
        }

        // Reconstruct path by following predecessor links from target back to source
        List<String> path = new ArrayList<>();
        for (String node = target; node != null; node = prev.get(node)) {
            path.add(0, node);
        }

        double totalLatency = dist.get(target);
        log.debug("shortestPath({} → {}) → {} hops, {}ms", source, target, path.size() - 1, totalLatency);
        return new ShortestPathResponse(source, target, List.copyOf(path), totalLatency, true);
    }

    // -------------------------------------------------------------------------
    // Critical services
    // -------------------------------------------------------------------------

    /**
     * Returns the top {@code k} services whose failure would have the greatest
     * operational impact, based solely on the current live graph state.
     *
     * <h3>Scoring</h3>
     * <p>Each candidate service (any node that is the target of at least one active
     * incoming edge) receives two scores derived from its direct incoming edges:
     * <ol>
     *   <li><b>dependentServiceCount</b> — the number of active upstream services
     *       that depend on it (primary ranking key, descending).</li>
     *   <li><b>impactedRequestCount</b> — the sum of {@link Edge#getRequestCount()}
     *       across all active incoming edges (tiebreaker, descending).</li>
     * </ol>
     *
     * <h3>Scope</h3>
     * <p>Only the current, live edge topology is considered.  Removed dependencies
     * and historical metadata are ignored.  Services with no active incoming edges
     * are excluded from the result.
     *
     * <h3>k handling</h3>
     * <p>If {@code k ≤ 0} an empty list is returned.  If {@code k} exceeds the number
     * of qualifying services, all qualifying services are returned.
     *
     * @param k maximum number of critical services to return
     * @return a {@link CriticalServicesResponse} with up to {@code k} entries,
     *         most critical first
     */
    public CriticalServicesResponse criticalServices(int k) {
        if (k <= 0) {
            return new CriticalServicesResponse(List.of());
        }

        Comparator<CriticalService> ranking =
                Comparator.comparingInt(CriticalService::getDependentServiceCount).reversed()
                          .thenComparing(
                                  Comparator.comparingLong(CriticalService::getImpactedRequestCount).reversed());

        List<CriticalService> topK = graphStore.getAllEdgeTargets().stream()
                .map(service -> {
                    Set<Edge> incoming = graphStore.getIncomingEdges(service);

                    int dependentCount = incoming.size();
                    long totalTraffic  = incoming.stream()
                                                 .mapToLong(Edge::getRequestCount)
                                                 .sum();
                    List<String> dependents = incoming.stream()
                                                      .map(Edge::getSource)
                                                      .sorted()
                                                      .collect(Collectors.toList());

                    return new CriticalService(service, dependentCount, totalTraffic,
                                              List.copyOf(dependents));
                })
                .sorted(ranking)
                .limit(k)
                .collect(Collectors.toList());

        log.debug("criticalServices(k={}) → {} results", k, topK.size());
        return new CriticalServicesResponse(List.copyOf(topK));
    }

    // -------------------------------------------------------------------------
    // Cycle detection
    // -------------------------------------------------------------------------

    /**
     * Detects and returns all distinct circular dependency cycles currently
     * present in the active service graph.
     *
     * <h3>Algorithm</h3>
     * <p>Depth-first search is launched independently from every service that
     * appears as the target of at least one active edge
     * ({@link GraphStore#getAllEdgeTargets()}).  Each DFS maintains:
     * <ul>
     *   <li><b>{@code inCurrentPath}</b> — a set of nodes in the current
     *       recursion stack, used for O(1) back-edge detection.</li>
     *   <li><b>{@code currentPath}</b> — an ordered list of the same nodes,
     *       used for cycle-path extraction via index lookup.</li>
     * </ul>
     * When a neighbour that is already in {@code inCurrentPath} is encountered,
     * a back-edge has been found: the cycle path is extracted as the sublist
     * from that neighbour's first occurrence to the end of the current path,
     * with the neighbour appended once more to make the closure explicit.
     * Recursion is <em>not</em> continued past a back-edge, so no path grows
     * longer than {@code N} nodes; termination is guaranteed.
     *
     * <h3>No global "fully-processed" set</h3>
     * <p>Unlike classic 3-colour DFS, this implementation does <em>not</em>
     * maintain a global "done" set across different starting points.  This
     * ensures that multi-path cycles (e.g. both {@code A→B→D→A} and
     * {@code A→C→D→A} in a diamond graph) are all discovered.
     *
     * <h3>Deduplication</h3>
     * <p>A canonical form is computed for each detected cycle by rotating the
     * node list so that the lexicographically smallest node comes first.  A
     * {@code HashSet} of canonical forms prevents the same logical cycle from
     * being reported more than once, regardless of which starting point
     * discovered it.
     *
     * <h3>Graph state</h3>
     * <p>Only active edges present at call time are analysed.  The graph is
     * never mutated.
     *
     * @return a {@link CyclesResponse} with one {@link CycleEntry} per distinct
     *         cycle; empty when the graph is acyclic
     */
    public CyclesResponse cycles() {
        Set<List<String>> seenCanonical = new HashSet<>();
        List<CycleEntry> cycleList = new ArrayList<>();

        for (String start : graphStore.getAllEdgeTargets()) {
            detectCyclesFrom(start, new HashSet<>(), new ArrayList<>(),
                             seenCanonical, cycleList);
        }

        log.debug("cycles() → {} distinct cycles found", cycleList.size());
        return new CyclesResponse(cycleList.size(), List.copyOf(cycleList));
    }

    /**
     * Recursive DFS helper for cycle detection.
     *
     * <p>When {@code current} is already in {@code inCurrentPath}, a back-edge
     * has been found.  The cycle is extracted, canonicalised, and — if not yet
     * seen — recorded.  Recursion stops at that point (no further expansion).
     * Otherwise {@code current} is added to the path and DFS continues through
     * its outgoing neighbours.
     *
     * @param current       node being visited in this call
     * @param inCurrentPath set of nodes in the active recursion stack (O(1) lookup)
     * @param currentPath   ordered list of nodes in the active recursion stack
     * @param seenCanonical canonical forms of already-recorded cycles (dedup guard)
     * @param results       accumulator for completed {@link CycleEntry} objects
     */
    private void detectCyclesFrom(String current,
                                  Set<String> inCurrentPath,
                                  List<String> currentPath,
                                  Set<List<String>> seenCanonical,
                                  List<CycleEntry> results) {

        if (inCurrentPath.contains(current)) {
            // Back-edge: current is already in the recursion stack — cycle found
            int cycleStart = currentPath.indexOf(current);
            List<String> cycleNodes = new ArrayList<>(
                    currentPath.subList(cycleStart, currentPath.size()));

            List<String> canonical = canonicalize(cycleNodes);
            if (seenCanonical.add(canonical)) {
                List<String> cyclePath = new ArrayList<>(cycleNodes);
                cyclePath.add(current); // close the cycle: repeat entry node
                results.add(new CycleEntry(
                        results.size() + 1, List.copyOf(cyclePath), cycleNodes.size()));
            }
            return; // do not recurse further past the back-edge
        }

        inCurrentPath.add(current);
        currentPath.add(current);

        for (String neighbor : graphStore.getOutgoingNeighbors(current)) {
            detectCyclesFrom(neighbor, inCurrentPath, currentPath, seenCanonical, results);
        }

        // Unwind: remove current from the recursion stack before returning
        currentPath.remove(currentPath.size() - 1);
        inCurrentPath.remove(current);
    }

    /**
     * Returns the canonical rotation of {@code cycleNodes}: the rotation that
     * places the lexicographically smallest node at index 0.  Two lists that
     * represent the same cycle (differing only in starting point) will always
     * produce identical canonical forms.
     *
     * @param cycleNodes the cycle's unique nodes in DFS-traversal order
     *                   (the closing duplicate must <em>not</em> be included)
     * @return a new list containing the same nodes in canonical rotation order
     */
    private List<String> canonicalize(List<String> cycleNodes) {
        int minIdx = 0;
        for (int i = 1; i < cycleNodes.size(); i++) {
            if (cycleNodes.get(i).compareTo(cycleNodes.get(minIdx)) < 0) {
                minIdx = i;
            }
        }
        List<String> canonical = new ArrayList<>(cycleNodes.size());
        int size = cycleNodes.size();
        for (int i = 0; i < size; i++) {
            canonical.add(cycleNodes.get((minIdx + i) % size));
        }
        return canonical;
    }

    // -------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------

    /**
     * Returns an operational health snapshot for {@code service} over the last
     * {@code window} of telemetry data by delegating to
     * {@link TelemetryStore#getStats(String, Duration)}.
     *
     * @param service the service name to analyse
     * @param window  look-back duration (e.g. {@code Duration.ofMinutes(5)})
     * @return a {@link HealthMetrics} with p95 latency, failure rate, and timeout rate;
     *         all zeros when no telemetry exists within the window
     */
    public HealthMetrics health(String service, Duration window) {
        return telemetryStore.getStats(service, window);
    }
}
