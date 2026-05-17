package com.defendermate.graph.controller;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Structured error body returned for all error responses from REST controllers.
 *
 * <pre>
 * {
 *   "timestamp": "2026-05-17T10:00:00Z",
 *   "status":    400,
 *   "error":     "Bad Request",
 *   "message":   "service must not be blank",
 *   "path":      "/api/graph/reachable/"
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Structured error response returned for all 4xx and 5xx responses")
public class ErrorResponse {

    @Schema(description = "UTC instant when this error response was generated",
            example = "2026-05-17T10:00:00Z")
    private Instant timestamp;

    @Schema(description = "HTTP status code", example = "400")
    private int status;

    @Schema(description = "Short HTTP reason phrase", example = "Bad Request")
    private String error;

    @Schema(description = "Human-readable description of what was wrong",
            example = "service must not be blank")
    private String message;

    @Schema(description = "The request URI that triggered this error",
            example = "/api/graph/reachable/")
    private String path;

}
