package com.defendermate.graph.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    private String eventId;
    private EventType type;
    private String source;
    private String target;
    private Long latencyMs;
    private EventStatus status;
    private Instant timestamp;
}
