package com.defendermate.graph.exception;

/**
 * Thrown when a shortest-path request is logically invalid — for example,
 * when {@code source} and {@code target} refer to the same service, or either
 * parameter is blank.
 *
 * <p>Maps to HTTP {@code 400 Bad Request}.
 *
 * <p>TODO: add a suggestion of the nearest reachable target once graph-proximity
 *          hints are surfaced by the query service.
 */
public class InvalidShortestPathException extends RuntimeException {

    /**
     * @param message a concise, user-facing description of why the path request is invalid
     */
    public InvalidShortestPathException(String message) {
        super(message);
    }
}
