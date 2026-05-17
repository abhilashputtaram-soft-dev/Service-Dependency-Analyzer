package com.defendermate.graph.exception;

/**
 * Thrown when the graph snapshot cannot be read from or written to the persistent store
 * due to an I/O or serialization error.
 *
 * <p>Maps to HTTP {@code 500 Internal Server Error}.
 *
 * <p>TODO: integrate with a dead-letter store or alerting system so that persistent
 *          snapshot failures are surfaced to the on-call team automatically.
 */
public class SnapshotPersistenceException extends RuntimeException {

    /**
     * @param message a concise description of the persistence failure
     */
    public SnapshotPersistenceException(String message) {
        super(message);
    }

    /**
     * @param message a concise description of the persistence failure
     * @param cause   the underlying I/O or deserialization exception
     */
    public SnapshotPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
