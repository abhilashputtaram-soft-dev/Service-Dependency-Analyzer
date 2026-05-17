package com.defendermate.graph.controller;

import com.defendermate.graph.exception.InvalidRequestException;
import com.defendermate.graph.ingestion.EventRequest;
import com.defendermate.graph.ingestion.IngestionService;
import com.defendermate.graph.ingestion.IngestionSummary;
import com.defendermate.graph.model.Event;
import com.defendermate.graph.persistence.SnapshotManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes the ingestion pipeline endpoints.
 *
 * <p>Responsible only for HTTP mapping and input validation. All business logic
 * is delegated to {@link IngestionService}. Invalid inputs are signalled by
 * throwing domain exceptions which are translated to structured JSON error
 * responses by the centralized
 * {@link com.defendermate.graph.exception.GlobalExceptionHandler}.
 */
@Slf4j
@RestController
@RequestMapping("/api/ingestion")
@Tag(name = "Ingestion", description = "Event ingestion pipeline — publish events that are processed into the dependency graph")
public class IngestionController {

    private final IngestionService ingestionService;
    private final SnapshotManager snapshotManager;

    public IngestionController(IngestionService ingestionService, SnapshotManager snapshotManager) {
        this.ingestionService = ingestionService;
        this.snapshotManager = snapshotManager;
    }

    /**
     * Generates and publishes the requested number of random events into the
     * ingestion queue, distributing work evenly across the configured producer threads.
     *
     * <pre>POST /api/ingestion/publish?count=10000</pre>
     *
     * @param count total number of events to generate; must be &gt; 0
     * @return {@code 200 OK} with an {@link IngestionSummary}
     * @throws InvalidRequestException if {@code count} is zero or negative
     */
    @Operation(
            summary = "Publish random events in bulk",
            description = "Generates the requested number of random service-dependency events and distributes " +
                    "them evenly across the configured producer threads for parallel ingestion. " +
                    "count must be greater than 0.",
            tags = {"Ingestion"})
    @ApiResponse(responseCode = "200", description = "Summary of the publish operation",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = IngestionSummary.class)))
    @ApiResponse(responseCode = "400", description = "count is zero or negative",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/publish")
    public ResponseEntity<IngestionSummary> publish(
            @Parameter(description = "Total number of random events to generate and enqueue; must be > 0",
                    example = "1000", required = true)
            @RequestParam int count) {
        if (count <= 0) {
            throw new InvalidRequestException("count must be greater than 0");
        }
        return ResponseEntity.ok(ingestionService.generateAndPublish(count));
    }

    /**
     * Publishes a single hand-crafted event directly into the ingestion queue.
     * Intended for integration testing and manual graph manipulation.
     *
     * <pre>
     * POST /api/ingestion/event
     * {
     *   "source":    "checkout-api",
     *   "target":    "payment-service",
     *   "type":      "DEPENDENCY_OBSERVED",
     *   "latencyMs": 42,
     *   "status":    "SUCCESS",
     *   "timestamp": "2026-05-17T10:00:00Z"
     * }
     * </pre>
     *
     * @param request the event payload; {@code source} and {@code type} are required
     * @return {@code 202 Accepted} with the enqueued event (including assigned eventId)
     * @throws InvalidRequestException if {@code source} or {@code type} is missing
     */
    @Operation(
            summary = "Publish a single hand-crafted event",
            description = "Enqueues one explicitly-defined event directly into the ingestion pipeline. " +
                    "Intended for integration testing and manual graph manipulation.\n\n" +
                    "**Required fields:** `source`, `type`.\n\n" +
                    "**Optional fields:** `eventId` (UUID auto-assigned if absent), `target`, " +
                    "`latencyMs`, `status` (defaults to OK), `timestamp` (defaults to now).\n\n" +
                    "Returns 409 Conflict when the provided `eventId` has already been processed.",
            tags = {"Ingestion"})
    @ApiResponse(responseCode = "202", description = "Event accepted and enqueued; body is the enqueued event with assigned eventId",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = Event.class)))
    @ApiResponse(responseCode = "400", description = "source is blank or type is null",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Duplicate eventId — the event has already been processed",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/event")
    public ResponseEntity<Event> publishEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Event payload. source and type are required; all other fields are optional.",
                    required = true)
            @RequestBody EventRequest request) {
        if (request.source() == null || request.source().isBlank()) {
            throw new InvalidRequestException("source must not be blank");
        }
        if (request.type() == null) {
            throw new InvalidRequestException("type must not be null");
        }
        return ResponseEntity.accepted().body(ingestionService.publishSingleEvent(request));
    }

    @Operation(
            summary = "Clear all in-memory data and snapshot",
            description = "Wipes the in-memory graph topology, telemetry records, and the processed-event " +
                    "deduplication set, then deletes the snapshot file from disk. " +
                    "The application is left in the same state as a fresh start with no prior snapshot.",
            tags = {"Ingestion"})
    @ApiResponse(responseCode = "204", description = "All data cleared successfully")
    @DeleteMapping("/data")
    public ResponseEntity<Void> clearAll() {
        log.info("Clear-all requested via ingestion endpoint");
        snapshotManager.clearAll();
        return ResponseEntity.noContent().build();
    }
}

