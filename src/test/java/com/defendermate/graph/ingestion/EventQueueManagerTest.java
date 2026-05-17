package com.defendermate.graph.ingestion;

import com.defendermate.graph.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class EventQueueManagerTest {

    private IngestionProperties properties;
    private EventQueueManager   queueManager;

    @BeforeEach
    void setUp() {
        properties = new IngestionProperties();
        properties.setQueueCapacity(10);
        queueManager = new EventQueueManager(properties);
    }

    private Event event(String id) {
        return new Event(id, EventType.DEPENDENCY_OBSERVED, "svc-a", "svc-b", 10L, EventStatus.OK, Instant.now());
    }

    // ==================================================================
    // Basic publish / consume
    // ==================================================================

    @Nested
    class BasicOperationTests {

        @Test
        void publishAndConsume_returnsSameEventInstance() throws InterruptedException {
            Event evt = event("e1");
            queueManager.publish(evt);

            assertThat(queueManager.consume(100, TimeUnit.MILLISECONDS)).isSameAs(evt);
        }

        @Test
        void size_reflectsNumberOfQueuedEvents() throws InterruptedException {
            queueManager.publish(event("e1"));
            queueManager.publish(event("e2"));
            queueManager.publish(event("e3"));

            assertThat(queueManager.size()).isEqualTo(3);
        }

        @Test
        void size_decreasesAfterConsume() throws InterruptedException {
            queueManager.publish(event("e1"));
            queueManager.publish(event("e2"));
            queueManager.tryConsume();

            assertThat(queueManager.size()).isEqualTo(1);
        }

        @Test
        void fifoOrder_isPreserved() throws InterruptedException {
            queueManager.publish(event("first"));
            queueManager.publish(event("second"));
            queueManager.publish(event("third"));

            assertThat(queueManager.consume(100, TimeUnit.MILLISECONDS).getEventId()).isEqualTo("first");
            assertThat(queueManager.consume(100, TimeUnit.MILLISECONDS).getEventId()).isEqualTo("second");
            assertThat(queueManager.consume(100, TimeUnit.MILLISECONDS).getEventId()).isEqualTo("third");
        }
    }

    // ==================================================================
    // Edge cases
    // ==================================================================

    @Nested
    class EdgeCaseTests {

        @Test
        void tryConsume_onEmptyQueue_returnsNull() {
            assertThat(queueManager.tryConsume()).isNull();
        }

        @Test
        void consume_withTimeout_onEmptyQueue_returnsNull() throws InterruptedException {
            assertThat(queueManager.consume(50, TimeUnit.MILLISECONDS)).isNull();
        }

        @Test
        void capacityOfOne_secondPublishBlocksUntilSlotIsFreed() throws InterruptedException {
            properties.setQueueCapacity(1);
            queueManager = new EventQueueManager(properties);

            queueManager.publish(event("fill"));  // occupies the only slot

            // This thread should block on the second publish until we free a slot
            Thread publisher = new Thread(() -> {
                try {
                    queueManager.publish(event("blocked"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            publisher.start();

            Thread.sleep(50); // give the thread time to reach the blocking put()
            assertThat(publisher.isAlive()).as("publisher should still be blocked").isTrue();

            queueManager.tryConsume(); // free the slot
            publisher.join(500);

            assertThat(publisher.isAlive()).as("publisher should have unblocked and finished").isFalse();
            assertThat(queueManager.size()).isEqualTo(1); // the unblocked event is now in the queue
        }

        @Test
        void minimumCapacityEnforced_zeroCapacityRaisedToOne() {
            properties.setQueueCapacity(0); // below minimum
            EventQueueManager manager = new EventQueueManager(properties);

            // Should not throw during construction; publish should work
            assertThatNoException().isThrownBy(() -> manager.publish(event("e1")));
        }
    }
}
