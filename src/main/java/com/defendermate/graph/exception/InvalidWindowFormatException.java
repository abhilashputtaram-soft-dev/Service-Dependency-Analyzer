package com.defendermate.graph.exception;

/**
 * Thrown when a {@code window} query parameter cannot be parsed as a valid look-back duration.
 *
 * <p>Accepted formats are a positive integer followed by {@code m} (minutes) or {@code h} (hours),
 * e.g. {@code "5m"}, {@code "2h"}.
 *
 * <p>Maps to HTTP {@code 400 Bad Request}.
 *
 * <p>TODO: include the rejected value as a structured field once multi-field error responses
 *          are supported, to give clients actionable feedback without a round-trip.
 */
public class InvalidWindowFormatException extends RuntimeException {

    /**
     * @param message a concise, user-facing description of the format requirement
     */
    public InvalidWindowFormatException(String message) {
        super(message);
    }
}
