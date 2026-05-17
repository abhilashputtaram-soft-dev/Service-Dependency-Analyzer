package com.defendermate.graph.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthMetrics {

    private String service;

    /** 95th-percentile latency in milliseconds over the queried window. */
    private long p95LatencyMs;

    /** Fraction of events with status ERROR  (0.0 – 1.0). */
    private double failureRate;

    /** Fraction of events with status TIMEOUT (0.0 – 1.0). */
    private double timeoutRate;
}
