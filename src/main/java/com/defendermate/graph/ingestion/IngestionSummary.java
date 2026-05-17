package com.defendermate.graph.ingestion;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body returned by {@code POST /api/ingestion/publish}.
 *
 * <p>Summarises the outcome of a single publish request: how many events were
 * requested, how many were actually enqueued (may be less if producers were
 * interrupted), which pipeline settings were active, and how long the publish
 * call took.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestionSummary {

    /** Total number of events requested by the caller. */
    private int requestedCount;

    /** Number of events successfully enqueued (≤ requestedCount). */
    private int publishedCount;

    /** Number of producer threads that shared the workload. */
    private int producerThreads;

    /** Configured capacity of the backing queue at the time of the request. */
    private int queueCapacity;

    /** Wall-clock time (ms) from the start of the publish call to its completion. */
    private long durationMs;
}
