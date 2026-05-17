package com.defendermate.graph.exception;

/**
 * Thrown when a request parameter fails validation — for example, a blank service name
 * or a non-positive {@code count} value.
 *
 * <p>Maps to HTTP {@code 400 Bad Request}.
 *
 * <p>TODO: consider adding a {@code field} attribute to carry the offending parameter name
 *          once multi-field error responses are supported.
 */
public class InvalidRequestException extends RuntimeException {

    /**
     * @param message a concise, user-facing description of the validation failure
     */
    public InvalidRequestException(String message) {
        super(message);
    }
}
