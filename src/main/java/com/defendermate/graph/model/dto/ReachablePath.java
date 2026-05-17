package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A single path from the query source to one reachable downstream service.
 *
 * <p>{@code path} is the ordered list of service names traversed to reach
 * {@code targetService}, including both the source and the target.
 *
 * <p>Example: source=checkout-api, target=bank-gateway
 * <pre>
 *   targetService: "bank-gateway"
 *   path: ["checkout-api", "payment-service", "bank-gateway"]
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReachablePath {

    /** The downstream service reached at the end of this path. */
    private String targetService;

    /** Ordered traversal from the query source to {@code targetService}, inclusive. */
    private List<String> path;
}
