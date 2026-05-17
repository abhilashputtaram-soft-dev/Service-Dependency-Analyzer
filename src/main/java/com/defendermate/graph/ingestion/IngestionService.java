package com.defendermate.graph.ingestion;

import com.defendermate.graph.exception.QueueProcessingException;
import com.defendermate.graph.model.Event;
import com.defendermate.graph.model.EventStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Service layer for the ingestion pipeline.
 *
 * <p>Encapsulates all business logic for publishing events: bulk random-event
 * generation via {@link EventProducer} and single-event construction, defaulting,
 * logging, and queue submission via {@link EventQueueManager}.
 */
@Slf4j
@Service
public class IngestionService {

    private final EventProducer producer;
    private final EventQueueManager queueManager;

    public IngestionService(EventProducer producer, EventQueueManager queueManager) {
        this.producer = producer;
        this.queueManager = queueManager;
    }

    /**
     * Generates and publishes {@code count} random events into the ingestion queue.
     *
     * @param count total number of events to generate; must be &gt; 0
     * @return a summary of the completed batch run
     */
    public IngestionSummary generateAndPublish(int count) {
        return producer.generateAndPublish(count);
    }

    /**
     * Constructs a single {@link Event} from the supplied request (applying defaults
     * for any absent optional fields), logs it, and publishes it to the queue.
     *
     * <p>Defaults applied when fields are absent:
     * <ul>
     *   <li>{@code eventId} — random UUID</li>
     *   <li>{@code status}  — {@link EventStatus#OK}</li>
     *   <li>{@code timestamp} — {@link Instant#now()}</li>
     * </ul>
     *
     * @param request the event payload; {@code source} and {@code type} must already
     *                be validated by the caller
     * @return the fully constructed event that was enqueued
     * @throws QueueProcessingException if the publishing thread is interrupted
     */
    public Event publishSingleEvent(EventRequest request) {
        Event event = new Event(
                (request.eventId() != null && !request.eventId().isBlank()) ? request.eventId() : UUID.randomUUID().toString(),
                request.type(),
                request.source(),
                request.target(),
                request.latencyMs(),
                request.status() != null ? request.status() : EventStatus.OK,
                request.timestamp() != null ? request.timestamp() : Instant.now()
        );

        log.info("Publishing single event eventId={} type={} source={} target={}",
                event.getEventId(), event.getType(), event.getSource(), event.getTarget());

        try {
            queueManager.publish(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueueProcessingException("Interrupted while publishing event to queue", e);
        }

        return event;
    }
}
