package com.defendermate.graph.ingestion;

import com.defendermate.graph.model.Event;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Continuously consumes {@link Event}s from the {@link EventQueueManager} and
 * delegates each one to {@link EventProcessor} for processing.
 *
 * <h3>Lifecycle</h3>
 * <p>Consumer threads are started automatically when the Spring context initialises
 * ({@link #start()}) and are stopped gracefully on application shutdown
 * ({@link #shutdown()}).
 *
 * <h3>Graceful shutdown</h3>
 * <ol>
 *   <li>The {@code running} flag is set to {@code false}, causing all consumer
 *       loops to exit after the next poll timeout.</li>
 *   <li>The {@link ExecutorService} is shut down and given up to 10 seconds for
 *       in-flight processing to complete.</li>
 *   <li>Any events still in the queue are drained synchronously on the calling
 *       thread so that no queued work is silently discarded on shutdown.</li>
 * </ol>
 *
 * <h3>Fault isolation</h3>
 * <p>Individual event failures are caught inside {@link EventProcessor#process(Event)};
 * this class only needs to handle {@link InterruptedException} from the queue poll.
 * A consumer thread that receives an interrupt exits cleanly without taking down
 * other consumer threads.
 */
@Slf4j
@Component
public class EventConsumer {

    /** Timeout for each queue-poll iteration. Keeps threads responsive to shutdown signals. */
    private static final long POLL_TIMEOUT_MS = 100;

    /** Maximum time (seconds) to wait for in-flight work to finish during shutdown. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 10;

    private final EventQueueManager queueManager;
    private final EventProcessor    processor;
    private final IngestionProperties properties;

    private final ExecutorService executorService;
    private volatile boolean running = false;

    public EventConsumer(EventQueueManager queueManager,
                         EventProcessor processor,
                         IngestionProperties properties) {
        this.queueManager = queueManager;
        this.processor    = processor;
        this.properties   = properties;

        int threads = Math.max(2, properties.getConsumerThreads());
        this.executorService = Executors.newFixedThreadPool(threads,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("event-consumer-" + t.threadId());
                    t.setDaemon(false);
                    return t;
                });
    }

    /**
     * Starts the configured number of consumer threads. Called automatically by Spring
     * after the bean is fully initialised.
     */
    @PostConstruct
    public void start() {
        int threads = Math.max(2, properties.getConsumerThreads());
        running = true;
        for (int i = 0; i < threads; i++) {
            executorService.submit(this::consumeLoop);
        }
        log.info("EventConsumer started with {} consumer threads", threads);
    }

    /**
     * Stops all consumer threads and drains any remaining events from the queue.
     * Called automatically by Spring on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        log.info("EventConsumer shutdown initiated remainingQueueSize={}", queueManager.size());
        running = false;
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Consumer threads did not terminate in {}s — forcing shutdown", SHUTDOWN_AWAIT_SECONDS);
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Drain events that arrived between the running=false assignment and thread exit
        drainRemaining();
        log.info("EventConsumer stopped");
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Main loop executed by each consumer thread. Polls the queue with a short
     * timeout on each iteration so the thread can check the {@code running} flag
     * and react to shutdown without stalling indefinitely.
     */
    private void consumeLoop() {
        String threadName = Thread.currentThread().getName();
        log.info("Consumer thread started thread={}", threadName);
        while (running) {
            try {
                Event event = queueManager.consume(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    processor.process(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Consumer thread interrupted thread={}", threadName);
                break;
            }
        }
        log.info("Consumer thread stopped thread={}", threadName);
    }

    /**
     * Non-blocking drain of any events still in the queue after all consumer
     * threads have stopped. Ensures no in-flight events are silently discarded.
     */
    private void drainRemaining() {
        Event event;
        int drained = 0;
        while ((event = queueManager.tryConsume()) != null) {
            processor.process(event);
            drained++;
        }
        if (drained > 0) {
            log.info("Drained {} remaining events during shutdown", drained);
        }
    }
}
