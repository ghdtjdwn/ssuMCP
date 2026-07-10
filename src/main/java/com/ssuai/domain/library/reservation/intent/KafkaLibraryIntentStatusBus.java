package com.ssuai.domain.library.reservation.intent;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

import com.ssuai.global.kafka.IntentBusKafkaProperties;

/**
 * Kafka-backed {@link LibraryIntentStatusBus} (Phase 2-C, ADR 0091). Graduates the cross-pod
 * reservation-notify fan-out from a Redisson RTopic to Kafka for durability + offset replay, while
 * preserving the exact publish/subscribe contract so {@code LibraryIntentSseRegistry} and
 * {@code LibraryReservationEventListener} are untouched.
 *
 * <p>Publish is <b>fail-open and non-blocking</b>: events are offloaded to a single-thread bounded
 * executor (FIFO preserves per-key send order; a Kafka outage sheds load via AbortPolicy instead of
 * ever stalling the @Scheduled relay thread). Reservations themselves proceed via the outbox
 * regardless — only the notification is delayed/dropped when the broker is down.
 *
 * <p>Subscribe uses a <b>unique consumer group per pod</b> (broadcast fan-out): every replica must
 * receive every event because a given intentId's SSE emitter may live on any pod. With
 * {@code auto.offset.reset=latest} on a fresh group each start, a pod streams "from now on"; the
 * ephemeral groups carry no meaningful offsets and Kafka reaps them after offsets.retention.minutes.
 * Duplicate delivery (at-least-once) is tolerated downstream: terminal events remove the intentId
 * key idempotently and a re-delivered non-terminal event just re-sends the same status.
 */
public class KafkaLibraryIntentStatusBus implements LibraryIntentStatusBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaLibraryIntentStatusBus.class);

    private static final String METRIC_NAME = "library.intent.bus.event";
    private static final String RESULT_SENT = "sent";
    private static final String RESULT_DROPPED_QUEUE_FULL = "dropped_queue_full";
    private static final String RESULT_DROPPED_ERROR = "dropped_error";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ConsumerFactory<String, String> consumerFactory;
    private final ObjectMapper objectMapper;
    private final IntentBusKafkaProperties properties;
    private final MeterRegistry registry;
    private final ThreadPoolExecutor executor;
    private final CopyOnWriteArrayList<KafkaMessageListenerContainer<String, String>> containers =
            new CopyOnWriteArrayList<>();

    public KafkaLibraryIntentStatusBus(
            KafkaTemplate<String, String> kafkaTemplate,
            ConsumerFactory<String, String> consumerFactory,
            ObjectMapper objectMapper,
            IntentBusKafkaProperties properties,
            MeterRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.consumerFactory = consumerFactory;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.registry = registry;
        // Single worker => strict FIFO => events for a given intentId are sent in the order the relay
        // produced them, which (with key=intentId partitioning + idempotent producer) preserves the
        // WAIT_REGISTERED -> SEAT_FOUND -> terminal ordering the SSE client depends on.
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                namedThreadFactory("intent-bus-kafka-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.prestartCoreThread();
    }

    @Override
    public void publish(LibraryIntentStatusMessage message) {
        try {
            executor.execute(() -> send(message));
        } catch (RejectedExecutionException ex) {
            increment(RESULT_DROPPED_QUEUE_FULL);
        } catch (Throwable ex) {
            increment(RESULT_DROPPED_ERROR);
        }
    }

    private void send(LibraryIntentStatusMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            String key = String.valueOf(message.intentId());
            kafkaTemplate.send(properties.getTopic(), key, json)
                    .whenComplete((metadata, ex) -> increment(ex == null ? RESULT_SENT : RESULT_DROPPED_ERROR));
        } catch (Throwable ex) {
            increment(RESULT_DROPPED_ERROR);
        }
    }

    @Override
    public Subscription subscribe(Consumer<LibraryIntentStatusMessage> listener) {
        String groupId = properties.getGroupPrefix() + "-" + UUID.randomUUID();
        ContainerProperties containerProperties = new ContainerProperties(properties.getTopic());
        containerProperties.setGroupId(groupId);
        containerProperties.setMessageListener((MessageListener<String, String>) record -> {
            try {
                listener.accept(objectMapper.readValue(record.value(), LibraryIntentStatusMessage.class));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
                log.warn("intent status subscriber failed: partition={} offset={}",
                        record.partition(), record.offset(), exception);
            }
        });
        KafkaMessageListenerContainer<String, String> container =
                new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setBeanName(groupId);
        container.start();
        containers.add(container);
        log.info("KafkaLibraryIntentStatusBus subscribed: topic={} group={}", properties.getTopic(), groupId);
        return () -> {
            containers.remove(container);
            container.stop();
        };
    }

    @PreDestroy
    void shutdown() {
        containers.forEach(container -> {
            try {
                container.stop();
            } catch (RuntimeException ignored) {
                // best-effort; JVM is going down
            }
        });
        containers.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private void increment(String result) {
        try {
            registry.counter(METRIC_NAME, "result", result).increment();
        } catch (Throwable ignored) {
        }
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
