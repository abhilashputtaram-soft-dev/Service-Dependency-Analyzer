package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Top-level response for a {@code criticalServices} query.
 *
 * <p>Contains the top-K most critical services ranked by the number of active
 * direct dependents, with request traffic as a tiebreaker.
 *
 * <p>Example:
 * <pre>
 * {
 *   "criticalServices": [
 *     {
 *       "serviceName":           "auth-service",
 *       "dependentServiceCount": 3,
 *       "impactedRequestCount":  250000,
 *       "affectedDependents":    ["checkout-api", "mobile-api", "profile-api"]
 *     }
 *   ]
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CriticalServicesResponse {

    /**
     * Ordered list of the top-K critical services, most critical first.
     * Empty when {@code k ≤ 0} or when the graph contains no services with
     * active incoming edges.  The list is immutable.
     */
    private List<CriticalService> criticalServices;
}
