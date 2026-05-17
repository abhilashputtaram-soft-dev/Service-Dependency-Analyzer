package com.defendermate.graph.ingestion;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized configuration for the ingestion pipeline, bound from properties
 * prefixed with {@code ingestion}.
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code ingestion.queue.capacity}   = 10 000</li>
 *   <li>{@code ingestion.producer.threads} = 2</li>
 *   <li>{@code ingestion.consumer.threads} = 2</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ingestion")
public class IngestionProperties {

    /** Maximum number of events the queue can hold before producers block. */
    private int queueCapacity = 10000;

    /** Number of concurrent threads publishing events into the queue. Minimum: 2. */
    private int producerThreads = 2;

    /** Number of concurrent threads draining and processing events from the queue. Minimum: 2. */
    private int consumerThreads = 2;
}
