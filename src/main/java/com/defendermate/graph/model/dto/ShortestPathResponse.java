package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response for a {@code shortestPath} query between two services.
 *
 * <p>When a path exists:
 * <pre>
 * {
 *   "sourceService":  "A",
 *   "targetService":  "D",
 *   "path":           ["A", "B", "D"],
 *   "totalLatencyMs": 40.0,
 *   "reachable":      true
 * }
 * </pre>
 *
 * <p>When no path exists (unreachable or unknown service):
 * <pre>
 * {
 *   "sourceService":  "A",
 *   "targetService":  "Z",
 *   "path":           [],
 *   "totalLatencyMs": -1.0,
 *   "reachable":      false
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShortestPathResponse {

    /** The service where the traversal starts. */
    private String sourceService;

    /** The service where the traversal ends. */
    private String targetService;

    /**
     * Ordered list of service names along the minimum-latency path, from
     * {@code sourceService} (first element) to {@code targetService} (last element).
     * Empty when {@code reachable} is {@code false}.  The list is immutable.
     */
    private List<String> path;

    /**
     * Sum of {@code avgLatencyMs} across all edges on the shortest path.
     * {@code -1.0} when {@code reachable} is {@code false}.
     */
    private double totalLatencyMs;

    /** {@code true} if a path from source to target was found; {@code false} otherwise. */
    private boolean reachable;
}
