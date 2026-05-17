package com.defendermate.graph.exception;

import com.defendermate.graph.controller.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * Centralized exception handler for all REST controllers in the service dependency graph system.
 *
 * <p>Converts domain and infrastructure exceptions into structured {@link ErrorResponse} bodies,
 * preventing stack traces or internal implementation details from leaking to API clients.
 *
 * <p>HTTP status mappings:
 * <ul>
 *   <li>{@code 400 Bad Request}          — validation failures and malformed parameters</li>
 *   <li>{@code 404 Not Found}            — references to unknown services</li>
 *   <li>{@code 409 Conflict}             — duplicate or conflicting event submissions</li>
 *   <li>{@code 500 Internal Server Error} — persistence and queue processing failures</li>
 * </ul>
 *
 * <p>Client-induced errors ({@code 4xx}) are logged at {@code WARN} level.
 * Server-side failures ({@code 5xx}) are logged at {@code ERROR} level with the full
 * stack trace to aid post-mortem analysis while keeping the API response clean.
 *
 * <p>TODO: integrate with a structured logging pipeline (e.g. ECS / JSON format) for
 *          production observability once the logging framework is configured.
 * <p>TODO: add a request-scoped correlation ID to error responses once a distributed
 *          tracing solution (e.g. OpenTelemetry) is in place.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // -------------------------------------------------------------------------
    // 400 Bad Request — validation and parameter errors
    // -------------------------------------------------------------------------

    /**
     * Handles generic request parameter validation failures (blank names, non-positive counts, etc.).
     *
     * @param ex      exception carrying the user-facing validation message
     * @param request current HTTP request, used to populate the {@code path} field
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            InvalidRequestException ex, HttpServletRequest request) {
        log.warn("Invalid request [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    /**
     * Handles malformed {@code window} duration parameters that do not match the
     * accepted {@code <int>m} / {@code <int>h} format.
     *
     * @param ex      exception carrying the user-facing format requirement message
     * @param request current HTTP request
     */
    @ExceptionHandler(InvalidWindowFormatException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWindowFormat(
            InvalidWindowFormatException ex, HttpServletRequest request) {
        log.warn("Invalid window format [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    /**
     * Handles logically invalid shortest-path requests — e.g. identical source and target.
     *
     * @param ex      exception carrying the user-facing description
     * @param request current HTTP request
     */
    @ExceptionHandler(InvalidShortestPathException.class)
    public ResponseEntity<ErrorResponse> handleInvalidShortestPath(
            InvalidShortestPathException ex, HttpServletRequest request) {
        log.warn("Invalid shortest-path request [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    /**
     * Handles non-positive {@code k} values in top-{@code k} queries.
     *
     * @param ex      exception carrying the user-facing constraint message
     * @param request current HTTP request
     */
    @ExceptionHandler(InvalidKValueException.class)
    public ResponseEntity<ErrorResponse> handleInvalidKValue(
            InvalidKValueException ex, HttpServletRequest request) {
        log.warn("Invalid k value [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    /**
     * Handles Spring MVC failures when a required query parameter is absent from the request.
     *
     * @param ex      Spring exception identifying the missing parameter name
     * @param request current HTTP request
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = "Required parameter '" + ex.getParameterName() + "' is missing";
        log.warn("Missing parameter [{}]: {}", request.getRequestURI(), message);
        return buildResponse(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    /**
     * Handles Spring MVC failures when a query parameter cannot be converted to the expected type.
     *
     * @param ex      Spring exception identifying the parameter name and required type
     * @param request current HTTP request
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String typeName = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "value";
        String message = "Parameter '" + ex.getName() + "' must be a valid " + typeName;
        log.warn("Type mismatch [{}]: {}", request.getRequestURI(), message);
        return buildResponse(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    // -------------------------------------------------------------------------
    // 404 Not Found — unknown service references
    // -------------------------------------------------------------------------

    /**
     * Handles references to service names that do not exist in the dependency graph.
     *
     * @param ex      exception identifying the unknown service
     * @param request current HTTP request
     */
    @ExceptionHandler(UnknownServiceException.class)
    public ResponseEntity<ErrorResponse> handleUnknownService(
            UnknownServiceException ex, HttpServletRequest request) {
        log.warn("Unknown service [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request.getRequestURI());
    }

    // -------------------------------------------------------------------------
    // 409 Conflict — duplicate or conflicting events
    // -------------------------------------------------------------------------

    /**
     * Handles duplicate event submissions where the event ID has already been processed.
     *
     * @param ex      exception describing the conflicting event
     * @param request current HTTP request
     */
    @ExceptionHandler(DuplicateEventException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateEvent(
            DuplicateEventException ex, HttpServletRequest request) {
        log.warn("Duplicate event [{}]: {}", request.getRequestURI(), ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    // -------------------------------------------------------------------------
    // 500 Internal Server Error — persistence and queue failures
    // -------------------------------------------------------------------------

    /**
     * Handles failures when reading or writing the graph snapshot to persistent storage.
     *
     * <p>The original cause is logged in full but a generic message is returned to the
     * client to avoid leaking filesystem paths or serialization details.
     *
     * @param ex      exception wrapping the underlying I/O or serialization error
     * @param request current HTTP request
     */
    @ExceptionHandler(SnapshotPersistenceException.class)
    public ResponseEntity<ErrorResponse> handleSnapshotPersistence(
            SnapshotPersistenceException ex, HttpServletRequest request) {
        log.error("Snapshot persistence failure [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "A persistence error occurred; please try again later", request.getRequestURI());
    }

    /**
     * Handles unrecoverable failures in the event queue processing pipeline.
     *
     * <p>The original cause is logged in full but a generic message is returned to the
     * client to avoid leaking queue internals.
     *
     * @param ex      exception wrapping the underlying queue error
     * @param request current HTTP request
     */
    @ExceptionHandler(QueueProcessingException.class)
    public ResponseEntity<ErrorResponse> handleQueueProcessing(
            QueueProcessingException ex, HttpServletRequest request) {
        log.error("Queue processing failure [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An event processing error occurred; please try again later", request.getRequestURI());
    }

    // -------------------------------------------------------------------------
    // 500 Fallback — unexpected runtime exceptions
    // -------------------------------------------------------------------------

    /**
     * Catch-all handler for any unexpected {@link Exception} not matched by a more specific
     * handler above.
     *
     * <p>The original exception message is intentionally suppressed in the response body
     * to avoid leaking internal implementation details to API clients.  The full stack
     * trace is always logged at {@code ERROR} level for post-mortem analysis.
     *
     * <p>TODO: forward a request-scoped error correlation ID to the client once a
     *          distributed tracing solution (e.g. OpenTelemetry) is integrated.
     *
     * @param ex      the unexpected exception
     * @param request current HTTP request
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error [{}]: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred; please contact support", request.getRequestURI());
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@link ResponseEntity} wrapping an {@link ErrorResponse} stamped with
     * the current UTC instant.
     *
     * @param status  the HTTP status to use for both the response line and the body
     * @param message the user-facing error description
     * @param path    the request URI that triggered the error
     * @return a fully-formed response entity ready to be serialized and returned
     */
    private ResponseEntity<ErrorResponse> buildResponse(HttpStatus status, String message, String path) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        );
        return ResponseEntity.status(status).body(body);
    }
}
