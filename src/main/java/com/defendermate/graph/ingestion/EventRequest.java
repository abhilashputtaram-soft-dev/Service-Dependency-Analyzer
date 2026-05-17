package com.defendermate.graph.ingestion;

import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.EventType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Request body for publishing a single hand-crafted event via the ingestion API.
 *
 * @param eventId   optional; a random UUID is assigned when absent
 * @param type      required; one of {@link EventType}
 * @param source    required; name of the originating service
 * @param target    optional; name of the dependency (relevant for DEPENDENCY_* types)
 * @param latencyMs optional; observed call latency in milliseconds
 * @param status    optional; defaults to {@link EventStatus#OK}
 * @param timestamp optional; defaults to the current wall-clock time
 */
@Schema(description = "Payload for publishing a single hand-crafted event into the ingestion pipeline")
public record EventRequest(
        @Schema(description = "Optional event identifier; a random UUID is assigned if absent or blank",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String eventId,

        @Schema(description = "Event type (required)", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "DEPENDENCY_OBSERVED")
        EventType type,

        @Schema(description = "Name of the originating service (required)",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "checkout-api")
        String source,

        @Schema(description = "Name of the dependency service; relevant for DEPENDENCY_OBSERVED and DEPENDENCY_REMOVED",
                example = "payment-service")
        String target,

        @Schema(description = "Observed round-trip call latency in milliseconds", example = "42")
        Long latencyMs,

        @Schema(description = "Call result status; defaults to OK when absent", example = "OK")
        EventStatus status,

        @Schema(description = "Event timestamp (ISO-8601); defaults to current server time when absent",
                example = "2026-05-17T10:00:00Z")
        Instant timestamp
) {}
