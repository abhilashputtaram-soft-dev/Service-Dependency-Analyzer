package com.defendermate.graph.ingestion;

import com.defendermate.graph.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Manages the bounded, thread-safe in-memory queue that connects event producers
 * to event consumers.
 *
 * <p>Uses a {@link LinkedBlockingQueue} as the backing store. The capacity is set once
 * at construction time from {@link IngestionProperties} and never changed.
 *
 * <h3>Backpressure</h3>
 * <p>{@link #publish(Event)} calls {@code put()}, which blocks the calling thread
 * when the queue is full. This provides natural backpressure: producers slow down
 * automatically instead of unboundedly growing memory.
 *
 * <h3>Thread safety</h3>
 * <p>{@link LinkedBlockingQueue} is fully thread-safe. Multiple producer and consumer
 * threads may call {@link #publish}/{@link #consume} concurrently without external
 * synchronization.
 */
@Slf4j
@Component
public class EventQueueManager {

    private final BlockingQueue<Event> queue;

    public EventQueueManager(IngestionProperties properties) {
        int capacity = Math.max(1, properties.getQueueCapacity());
        this.queue = new LinkedBlockingQueue<>(capacity);
        log.info("EventQueueManager initialised with capacity={}", capacity);
    }

    /**
     * Publishes an event into the queue.
     *
     * <p>Blocks if the queue is at capacity until space becomes available
     * (backpressure) or the calling thread is interrupted.
     *
     * @param event the event to enqueue; must not be {@code null}
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void publish(Event event) throws InterruptedException {
        queue.put(event);
    }

    /**
     * Retrieves and removes the head of the queue, waiting up to {@code timeout}
     * for an event to become available.
     *
     * @param timeout maximum time to wait
     * @param unit    time unit of the timeout argument
     * @return the next event, or {@code null} if the timeout elapsed before one arrived
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public Event consume(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * Retrieves and removes the head of the queue immediately, returning {@code null}
     * if the queue is empty. Used during drain-on-shutdown.
     *
     * @return the next event, or {@code null} if the queue is empty
     */
    public Event tryConsume() {
        return queue.poll();
    }

    /** Returns the current number of events waiting in the queue. */
    public int size() {
        return queue.size();
    }

    /** Returns the number of additional events the queue can accept without blocking. */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
}
