package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Top-level response for a {@code cycles} query.
 *
 * <p>Contains every distinct circular dependency cycle currently present
 * in the active service graph.
 *
 * <p>Example:
 * <pre>
 * {
 *   "totalCycles": 1,
 *   "cycles": [
 *     {
 *       "cycleId":     1,
 *       "path":        ["A", "B", "C", "A"],
 *       "cycleLength": 3
 *     }
 *   ]
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CyclesResponse {

    /** Total number of distinct cycles found; equals {@code cycles.size()}. */
    private int totalCycles;

    /**
     * One entry per distinct cycle, in discovery order.  The list is immutable.
     * Empty when the graph contains no circular dependencies.
     */
    private List<CycleEntry> cycles;
}
