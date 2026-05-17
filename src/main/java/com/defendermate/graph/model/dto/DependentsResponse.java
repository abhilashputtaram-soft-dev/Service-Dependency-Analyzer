package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Top-level response for a {@code dependents} query.
 *
 * <p>Contains every upstream service that directly or indirectly depends on
 * {@link #targetService}, each with its traversal path.
 *
 * <p>Example:
 * <pre>
 * {
 *   "targetService": "payment-service",
 *   "totalDependents": 2,
 *   "dependents": [
 *     { "dependentService": "checkout-api",  "path": ["checkout-api",  "payment-service"] },
 *     { "dependentService": "order-service", "path": ["order-service", "payment-service"] }
 *   ]
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DependentsResponse {

    /** The service whose upstream dependents were queried. */
    private String targetService;

    /** Total number of distinct upstream services found; equals {@code dependents.size()}. */
    private int totalDependents;

    /** One entry per reachable upstream dependent service, in DFS-discovery order. */
    private List<DependentPath> dependents;
}
