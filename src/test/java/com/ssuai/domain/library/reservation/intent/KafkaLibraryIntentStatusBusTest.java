package com.ssuai.domain.library.reservation.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import com.ssuai.global.kafka.IntentBusKafkaProperties;

class KafkaLibraryIntentStatusBusTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @SuppressWarnings("unchecked")
    void publishSendsWithIntentIdKeyAndCountsSent() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaLibraryIntentStatusBus bus = bus(kafkaTemplate, registry, 10);

        try {
            bus.publish(new LibraryIntentStatusMessage(
                    42L, LibraryReservationIntentEventType.SEAT_FOUND, Instant.parse("2026-07-10T00:00:00Z")));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter(registry, "sent")).isEqualTo(1.0));

            // key = intentId => same reservation's events land on one partition => order preserved.
            verify(kafkaTemplate).send(eq("library.reservation.events.v1"), eq("42"), anyString());
        } finally {
            bus.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishIsFailOpenWhenSendThrows() {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("broker metadata unavailable"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaLibraryIntentStatusBus bus = bus(kafkaTemplate, registry, 10);

        try {
            assertThatCode(() -> bus.publish(new LibraryIntentStatusMessage(
                    7L, LibraryReservationIntentEventType.RESERVATION_SUCCEEDED, Instant.EPOCH)))
                    .doesNotThrowAnyException();

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter(registry, "dropped_error")).isEqualTo(1.0));
        } finally {
            bus.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishDropsWhenBoundedQueueIsFull() throws InterruptedException {
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            sendEntered.countDown();
            releaseSend.await(5, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(null);
        });

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // capacity 1 + single worker: first task occupies the worker, second fills the queue, third is dropped.
        KafkaLibraryIntentStatusBus bus = bus(kafkaTemplate, registry, 1);

        try {
            bus.publish(message(1L));
            assertThat(sendEntered.await(2, TimeUnit.SECONDS)).isTrue();
            bus.publish(message(2L));
            bus.publish(message(3L));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter(registry, "dropped_queue_full")).isEqualTo(1.0));
        } finally {
            releaseSend.countDown();
            bus.shutdown();
        }
    }

    private static KafkaLibraryIntentStatusBus bus(
            KafkaTemplate<String, String> kafkaTemplate, SimpleMeterRegistry registry, int queueCapacity) {
        IntentBusKafkaProperties properties = new IntentBusKafkaProperties();
        properties.setQueueCapacity(queueCapacity);
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, String> consumerFactory = mock(ConsumerFactory.class);
        return new KafkaLibraryIntentStatusBus(kafkaTemplate, consumerFactory, MAPPER, properties, registry);
    }

    private static LibraryIntentStatusMessage message(long intentId) {
        return new LibraryIntentStatusMessage(
                intentId, LibraryReservationIntentEventType.WAIT_REGISTERED, Instant.EPOCH);
    }

    private static double counter(SimpleMeterRegistry registry, String result) {
        return registry.find("library.intent.bus.event").tag("result", result).counter().count();
    }
}
