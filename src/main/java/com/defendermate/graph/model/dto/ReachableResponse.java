package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for a reachability query originating from one service.
 *
 * <p>Contains one {@link ReachablePath} entry per unique downstream service
 * reachable from {@code sourceService} by following outgoing dependency edges.
 * Each entry records the DFS-first path taken to reach that service.
 *
 * <p>When {@code sourceService} is unknown or has no outgoing edges,
 * {@code paths} is empty and {@code totalReachableServices} is 0.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReachableResponse {

    /** The service from which the reachability query was issued. */
    private String sourceService;

    /** Total number of unique downstream services reachable from {@code sourceService}. */
    private int totalReachableServices;

    /**
     * One entry per reachable service, ordered by DFS discovery.
     * Never {@code null}; empty when no downstream services exist.
     */
    private List<ReachablePath> paths;
}
