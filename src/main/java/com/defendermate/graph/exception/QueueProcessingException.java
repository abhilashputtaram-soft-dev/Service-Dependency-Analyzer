package com.defendermate.graph.exception;

/**
 * Thrown when the event queue encounters an unrecoverable error during ingestion processing —
 * for example, when the queue thread is interrupted or an unexpected condition prevents
 * an event from being enqueued.
 *
 * <p>Maps to HTTP {@code 500 Internal Server Error}.
 *
 * <p>TODO: integrate with a circuit-breaker or retry/backoff policy once the ingestion
 *          pipeline supports configurable resilience strategies.
 */
public class QueueProcessingException extends RuntimeException {

    /**
     * @param message a concise description of the queue failure
     */
    public QueueProcessingException(String message) {
        super(message);
    }

    /**
     * @param message a concise description of the queue failure
     * @param cause   the underlying exception that caused the queue operation to fail
     */
    public QueueProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
