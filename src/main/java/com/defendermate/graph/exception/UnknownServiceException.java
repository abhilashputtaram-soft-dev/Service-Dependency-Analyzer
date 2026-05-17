package com.defendermate.graph.exception;

/**
 * Thrown when a request references a service name that does not exist in the dependency graph.
 *
 * <p>Maps to HTTP {@code 404 Not Found}.
 *
 * <p>TODO: enrich the response with a list of similarly-named known services once a
 *          fuzzy-search or autocomplete capability is added to the graph store.
 */
public class UnknownServiceException extends RuntimeException {

    /**
     * @param message a concise, user-facing description identifying the missing service
     */
    public UnknownServiceException(String message) {
        super(message);
    }
}
