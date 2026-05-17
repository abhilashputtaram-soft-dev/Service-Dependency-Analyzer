package com.defendermate.graph.ingestion;

import com.defendermate.graph.exception.QueueProcessingException;
import com.defendermate.graph.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock EventProducer    producer;
    @Mock EventQueueManager queueManager;

    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = new IngestionService(producer, queueManager);
    }

    private EventRequest request(String id, EventType type, EventStatus status, Instant timestamp) {
        return new EventRequest(id, type, "svc-a", "svc-b", 100L, status, timestamp);
    }

    // ==================================================================
    // Default field values when request fields are absent
    // ==================================================================

    @Nested
    class DefaultFieldTests {

        @Test
        void nullEventId_isDefaultedToNonBlankUuid() throws InterruptedException {
            Event result = service.publishSingleEvent(
                    request(null, EventType.DEPENDENCY_OBSERVED, EventStatus.OK, Instant.now()));

            assertThat(result.getEventId()).isNotNull().isNotBlank();
        }

        @Test
        void blankEventId_isDefaultedToNewUuid() throws InterruptedException {
            Event result = service.publishSingleEvent(
                    request("   ", EventType.DEPENDENCY_OBSERVED, EventStatus.OK, Instant.now()));

            assertThat(result.getEventId()).isNotBlank();
        }

        @Test
        void nullStatus_isDefaultedToOk() throws InterruptedException {
            Event result = service.publishSingleEvent(
                    request("id-1", EventType.DEPENDENCY_OBSERVED, null, Instant.now()));

            assertThat(result.getStatus()).isEqualTo(EventStatus.OK);
        }

        @Test
        void nullTimestamp_isDefaultedToCurrentTime() throws InterruptedException {
            Instant before = Instant.now();

            Event result = service.publishSingleEvent(
                    request("id-1", EventType.DEPENDENCY_OBSERVED, EventStatus.OK, null));

            assertThat(result.getTimestamp())
                    .isNotNull()
                    .isAfterOrEqualTo(before);
        }
    }

    // ==================================================================
    // Explicit fields are passed through unchanged
    // ==================================================================

    @Nested
    class PassthroughFieldTests {

        @Test
        void explicitEventId_isPreserved() throws InterruptedException {
            Event result = service.publishSingleEvent(
                    request("my-custom-id", EventType.DEPENDENCY_OBSERVED, EventStatus.OK, Instant.now()));

            assertThat(result.getEventId()).isEqualTo("my-custom-id");
        }

        @Test
        void explicitStatus_isPreserved() throws InterruptedException {
            Event result = service.publishSingleEvent(
                    request("id-1", EventType.DEPENDENCY_OBSERVED, EventStatus.ERROR, Instant.now()));

            assertThat(result.getStatus()).isEqualTo(EventStatus.ERROR);
        }

        @Test
        void explicitTimestamp_isPreserved() throws InterruptedException {
            Instant ts = Instant.parse("2026-01-01T00:00:00Z");

            Event result = service.publishSingleEvent(
                    request("id-1", EventType.DEPENDENCY_OBSERVED, EventStatus.OK, ts));

            assertThat(result.getTimestamp()).isEqualTo(ts);
        }
    }

    // ==================================================================
    // Queue interaction
    // ==================================================================

    @Nested
    class QueueInteractionTests {

        @Test
        void publishSingleEvent_delegatesBuiltEventToQueueManager() throws InterruptedException {
            ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);

            service.publishSingleEvent(request("id-1", EventType.DEPENDENCY_OBSERVED, EventStatus.OK, Instant.now()));

            verify(queueManager).publish(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo("id-1");
        }

        @Test
        void queueInterrupt_throwsQueueProcessingException() throws InterruptedException {
            doThrow(new InterruptedException()).when(queueManager).publish(any());

            assertThatThrownBy(() ->
                    service.publishSingleEvent(
                            request("id-1", EventType.DEPENDENCY_OBSERVED, EventStatus.OK, Instant.now()))
            ).isInstanceOf(QueueProcessingException.class);
        }

        @Test
        void queueInterrupt_restoresThreadInterruptFlag() throws InterruptedException {
            doThrow(new InterruptedException()).when(queueManager).publish(any());

            try {
                service.publishSingleEvent(
                        request("id-1", EventType.DEPENDENCY_OBSERVED, EventStatus.OK, Instant.now()));
            } catch (QueueProcessingException ignored) {}

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted(); // clean up so we don't pollute subsequent tests
        }
    }
}
