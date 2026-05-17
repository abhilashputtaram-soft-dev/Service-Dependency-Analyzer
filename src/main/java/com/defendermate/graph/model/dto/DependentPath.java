package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a single upstream service that (directly or indirectly) depends on
 * a queried target service, together with the traversal path from that upstream
 * service down to the target.
 *
 * <p>Example — given {@code checkout-api → payment-service}:
 * <pre>
 * {
 *   "dependentService": "checkout-api",
 *   "path": ["checkout-api", "payment-service"]
 * }
 * </pre>
 * The {@code path} list is always ordered from the upstream dependent service
 * (first element) to the queried target service (last element).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DependentPath {

    /** The upstream service that depends (directly or transitively) on the target. */
    private String dependentService;

    /**
     * Ordered traversal path from {@code dependentService} to the target service,
     * inclusive of both endpoints.  The list is immutable.
     */
    private List<String> path;
}
