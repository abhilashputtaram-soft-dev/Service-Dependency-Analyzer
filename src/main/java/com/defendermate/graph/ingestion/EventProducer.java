package com.defendermate.graph.ingestion;

import com.defendermate.graph.model.Event;
import com.defendermate.graph.model.EventStatus;
import com.defendermate.graph.model.EventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates random {@link Event}s and publishes them into the {@link EventQueueManager}.
 *
 * <h3>Workload distribution</h3>
 * <p>Each call to {@link #generateAndPublish(int)} spins up a fresh, short-lived
 * {@link ExecutorService} with the configured number of producer threads, divides
 * the requested event count evenly across them (remainder goes to the first thread),
 * and waits for all threads to finish before returning the summary.
 *
 * <h3>Backpressure</h3>
 * <p>Each producer thread calls {@link EventQueueManager#publish(Event)}, which
 * blocks when the queue is full. This propagates backpressure naturally: producers
 * slow down to match consumer throughput rather than exhausting heap memory.
 *
 * <h3>Event distribution</h3>
 * <ul>
 *   <li>{@code DEPENDENCY_OBSERVED} — 50 %</li>
 *   <li>{@code SERVICE_METADATA}    — 20 %</li>
 *   <li>{@code HEARTBEAT}           — 20 %</li>
 *   <li>{@code DEPENDENCY_REMOVED}  — 10 %</li>
 * </ul>
 */
@Slf4j
@Component
public class EventProducer {

    /** Realistic pool of service names used as event sources and targets. */
    private static final List<String> SERVICES = List.of(
            "payment-service", "auth-service", "order-service", "inventory-service",
            "notification-service", "user-service", "cart-service", "catalog-service",
            "shipping-service", "billing-service", "gateway-api", "search-service",
            "recommendation-service", "analytics-service", "cache-service"
    );

    private static final String[] TEAMS   = {"platform", "payments", "commerce", "infra", "data"};
    private static final String[] TIERS   = {"tier-1", "tier-2", "tier-3"};
    private static final String[] REGIONS = {"us-east-1", "eu-west-1", "ap-south-1"};

    private final EventQueueManager queueManager;
    private final IngestionProperties properties;

    public EventProducer(EventQueueManager queueManager, IngestionProperties properties) {
        this.queueManager = queueManager;
        this.properties   = properties;
    }

    /**
     * Generates {@code count} random events and publishes them into the queue,
     * distributing the workload evenly across the configured number of producer threads.
     *
     * <p>Blocks until all events have been successfully enqueued (or an
     * interruption forces an early exit).
     *
     * @param count total number of events to generate; must be &gt; 0
     * @return an {@link IngestionSummary} describing the outcome of the request
     */
    /**
     * Threshold in milliseconds above which the ingestion batch duration is logged at WARN
     * to flag unexpectedly slow publish runs (e.g. heavy backpressure from a full queue).
     */
    private static final long SLOW_BATCH_WARN_MS = 5_000;

    public IngestionSummary generateAndPublish(int count) {
        long startMs = System.currentTimeMillis();
        int threads  = Math.max(2, properties.getProducerThreads());
        int capacity = properties.getQueueCapacity();

        log.info("Ingestion batch starting requestedCount={} threads={} queueCapacity={} currentQueueSize={}",
                count, threads, capacity, queueManager.size());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger published  = new AtomicInteger(0);

        int perThread = count / threads;
        int remainder = count % threads;

        List<Future<?>> futures = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            int toGenerate = perThread + (i == 0 ? remainder : 0);
            futures.add(executor.submit(() -> publishBatch(toGenerate, published)));
        }

        awaitAll(futures);
        executor.shutdown();

        long durationMs = System.currentTimeMillis() - startMs;
        int remainingQueueSize = queueManager.size();

        if (durationMs > SLOW_BATCH_WARN_MS) {
            log.warn("Ingestion batch completed slowly requestedCount={} publishedCount={} threads={} durationMs={} remainingQueueSize={}",
                    count, published.get(), threads, durationMs, remainingQueueSize);
        } else {
            log.info("Ingestion batch completed requestedCount={} publishedCount={} threads={} durationMs={} remainingQueueSize={}",
                    count, published.get(), threads, durationMs, remainingQueueSize);
        }

        return new IngestionSummary(count, published.get(), threads, capacity, durationMs);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Generates and publishes {@code n} events on the calling thread, incrementing
     * {@code counter} for each successfully enqueued event.
     *
     * <p>Logs the thread lifecycle at INFO and individual publish failures at WARN.
     *
     * @param n       number of events this thread is responsible for
     * @param counter shared atomic counter tracking total published events across all threads
     */
    private void publishBatch(int n, AtomicInteger counter) {
        String threadName = Thread.currentThread().getName();
        log.info("Producer thread started thread={} eventsToPublish={}", threadName, n);
        for (int i = 0; i < n; i++) {
            try {
                Event event = generateEvent();
                queueManager.publish(event);
                counter.incrementAndGet();
                log.debug("Event published thread={} eventId={} type={} source={}",
                        threadName, event.getEventId(), event.getType(), event.getSource());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Producer thread interrupted thread={} publishedSoFar={}",
                        threadName, counter.get());
                return;
            }
        }
        log.info("Producer thread completed thread={} published={}", threadName, n);
    }

    /**
     * Waits for every {@link Future} to complete, logging execution failures at ERROR.
     *
     * @param futures the producer futures to await
     */
    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.error("Producer thread failed unexpectedly: {}", e.getCause().getMessage(), e.getCause());
            }
        }
    }

    /**
     * Generates a single random {@link Event} with realistic field values.
     *
     * <p>Event-type distribution:
     * <ul>
     *   <li>0–49  → {@code DEPENDENCY_OBSERVED}</li>
     *   <li>50–59 → {@code DEPENDENCY_REMOVED}</li>
     *   <li>60–79 → {@code SERVICE_METADATA}</li>
     *   <li>80–99 → {@code HEARTBEAT}</li>
     * </ul>
     */
    private Event generateEvent() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        EventType type = randomEventType(rng);

        String source = randomService(rng);
        String target = null;
        Long   latencyMs = null;
        EventStatus status = null;

        switch (type) {
            case DEPENDENCY_OBSERVED -> {
                target    = randomOtherService(rng, source);
                latencyMs = (long) rng.nextInt(10, 5_001);
                status    = randomStatus(rng);
            }
            case DEPENDENCY_REMOVED -> {
                target = randomOtherService(rng, source);
            }
            case HEARTBEAT -> {
                latencyMs = (long) rng.nextInt(1, 201);
                status    = randomStatus(rng);
            }
            case SERVICE_METADATA -> {
                // source/metadata only — no target or latency
            }
        }

        return new Event(
                UUID.randomUUID().toString(),
                type,
                source,
                target,
                latencyMs,
                status,
                Instant.now()
        );
    }

    private EventType randomEventType(ThreadLocalRandom rng) {
        int roll = rng.nextInt(100);
        if (roll < 50) return EventType.DEPENDENCY_OBSERVED;
        if (roll < 60) return EventType.DEPENDENCY_REMOVED;
        if (roll < 80) return EventType.SERVICE_METADATA;
        return EventType.HEARTBEAT;
    }

    private EventStatus randomStatus(ThreadLocalRandom rng) {
        int roll = rng.nextInt(100);
        if (roll < 70) return EventStatus.OK;
        if (roll < 85) return EventStatus.ERROR;
        return EventStatus.TIMEOUT;
    }

    private String randomService(ThreadLocalRandom rng) {
        return SERVICES.get(rng.nextInt(SERVICES.size()));
    }

    /** Returns a service name that is guaranteed to differ from {@code exclude}. */
    private String randomOtherService(ThreadLocalRandom rng, String exclude) {
        String target;
        do {
            target = randomService(rng);
        } while (target.equals(exclude));
        return target;
    }
}
