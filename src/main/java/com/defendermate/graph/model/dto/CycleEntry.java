package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Describes a single detected circular dependency cycle in the service graph.
 *
 * <p>The {@code path} list represents the full traversal route, with the cycle-entry
 * service repeated as the last element to make the closure explicit.
 *
 * <p>Example — cycle A → B → C → A:
 * <pre>
 * {
 *   "cycleId":     1,
 *   "path":        ["A", "B", "C", "A"],
 *   "cycleLength": 3
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CycleEntry {

    /** Sequential identifier assigned to this cycle in the order it was discovered. */
    private int cycleId;

    /**
     * Ordered list of service names along the cycle.  The first and last elements
     * are the same service (the cycle-entry point), making the closure explicit.
     * Length is always {@code cycleLength + 1}.  The list is immutable.
     */
    private List<String> path;

    /**
     * Number of distinct services involved in the cycle.  Equals
     * {@code path.size() - 1} since the entry service is counted once.
     */
    private int cycleLength;
}
