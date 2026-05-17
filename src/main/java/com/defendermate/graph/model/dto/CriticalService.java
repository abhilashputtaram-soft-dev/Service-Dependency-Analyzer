package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Describes one service's operational criticality based on the current live graph state.
 *
 * <p>Example:
 * <pre>
 * {
 *   "serviceName":           "auth-service",
 *   "dependentServiceCount": 3,
 *   "impactedRequestCount":  250000,
 *   "affectedDependents":    ["checkout-api", "mobile-api", "profile-api"]
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CriticalService {

    /** Name of the service being ranked. */
    private String serviceName;

    /**
     * Number of active services that directly depend on this service
     * (i.e., the count of incoming edges in the current graph).
     */
    private int dependentServiceCount;

    /**
     * Total request traffic flowing into this service across all active incoming
     * edges, computed as the sum of {@code Edge.requestCount} for each direct upstream
     * dependency.  Represents the operational blast-radius in requests.
     */
    private long impactedRequestCount;

    /**
     * Sorted list of service names that directly depend on this service and would be
     * affected if this service failed.  The list is immutable.
     */
    private List<String> affectedDependents;
}
