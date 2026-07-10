package com.ssuai.global.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import com.ssuai.domain.library.reservation.intent.KafkaLibraryIntentStatusBus;
import com.ssuai.domain.library.reservation.intent.LibraryIntentStatusBus;
import com.ssuai.domain.library.reservation.intent.LibraryIntentStatusMessage;
import com.ssuai.domain.library.reservation.intent.LibraryReservationIntentEventType;

/**
 * End-to-end regression guard for the Phase 2-C intent-status bus over a real (embedded) broker:
 * publish -> Kafka -> per-pod consumer -> subscriber, preserving JSON payload, enum/Instant, and the
 * per-intentId ordering the SSE client depends on.
 */
@DirtiesContext
@EmbeddedKafka(
        partitions = 1,
        topics = "library.reservation.events.v1",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@SpringBootTest(
        classes = {
                ToolCallKafkaConfig.class,
                IntentBusKafkaConfig.class,
                LibraryIntentStatusBusKafkaIT.TestConfig.class
        },
        properties = {
                "ssuai.kafka.enabled=true",
                "ssuai.kafka.topic=mcp.toolcall.events.v1",
                "ssuai.kafka.partitions=1",
                "ssuai.kafka.intent-bus.enabled=true",
                "ssuai.kafka.intent-bus.topic=library.reservation.events.v1",
                "ssuai.kafka.intent-bus.partitions=1",
                // earliest => deterministic regardless of when the container finishes assignment.
                "ssuai.kafka.intent-bus.auto-offset-reset=earliest"
        })
class LibraryIntentStatusBusKafkaIT {

    private final KafkaLibraryIntentStatusBus bus;

    @Autowired
    LibraryIntentStatusBusKafkaIT(KafkaLibraryIntentStatusBus bus) {
        this.bus = bus;
    }

    @Test
    void publishSubscribeRoundTripPreservesOrderPayloadAndInstant() {
        long intentId = 100L;
        Instant timestamp = Instant.parse("2026-07-10T00:00:00Z");
        List<LibraryIntentStatusMessage> received = new CopyOnWriteArrayList<>();

        LibraryIntentStatusBus.Subscription subscription = bus.subscribe(message -> {
            if (message.intentId() == intentId) {
                received.add(message);
            }
        });

        try {
            bus.publish(new LibraryIntentStatusMessage(
                    intentId, LibraryReservationIntentEventType.WAIT_REGISTERED, timestamp));
            bus.publish(new LibraryIntentStatusMessage(
                    intentId, LibraryReservationIntentEventType.SEAT_FOUND, timestamp));
            bus.publish(new LibraryIntentStatusMessage(
                    intentId, LibraryReservationIntentEventType.RESERVATION_SUCCEEDED, timestamp));

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(received).hasSize(3));

            assertThat(received)
                    .extracting(LibraryIntentStatusMessage::eventType)
                    .containsExactly(
                            LibraryReservationIntentEventType.WAIT_REGISTERED,
                            LibraryReservationIntentEventType.SEAT_FOUND,
                            LibraryReservationIntentEventType.RESERVATION_SUCCEEDED);
            assertThat(received.get(0).timestamp()).isEqualTo(timestamp);
        } finally {
            subscription.close();
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
