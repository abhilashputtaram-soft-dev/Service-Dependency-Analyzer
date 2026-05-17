package com.defendermate.graph.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Edge {

    private String source;
    private String target;
    private double avgLatencyMs;
    private long requestCount;
    private long errorCount;
    private Instant lastSeen;
}
