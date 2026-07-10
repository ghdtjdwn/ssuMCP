package com.ssuai.global.kafka;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.util.StringUtils;

import com.ssuai.domain.library.reservation.intent.KafkaLibraryIntentStatusBus;

/**
 * Wires the Kafka-backed intent-status bus (Phase 2-C, ADR 0091). Active only when
 * {@code ssuai.kafka.intent-bus.enabled=true}, which additionally requires the tool-call broker
 * beans ({@code ssuai.kafka.enabled=true}) to exist — the bus reuses the shared
 * {@code KafkaTemplate<String,String>} producer rather than opening a second one.
 *
 * <p>The consumer factory carries no fixed group.id: every subscription overrides it with a unique
 * per-pod group via {@code ContainerProperties.setGroupId} so the topic fans out (broadcast) to all
 * replicas. {@code auto.offset.reset=latest} means a fresh pod streams "from now on".
 */
@Configuration
@EnableConfigurationProperties(IntentBusKafkaProperties.class)
@ConditionalOnProperty(prefix = "ssuai.kafka.intent-bus", name = "enabled", havingValue = "true")
class IntentBusKafkaConfig {

    private final Environment environment;

    IntentBusKafkaConfig(Environment environment) {
        this.environment = environment;
    }

    @Bean
    ConsumerFactory<String, String> intentBusConsumerFactory(
            IntentBusKafkaProperties properties,
            MeterRegistry meterRegistry) {

        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, properties.getAutoOffsetReset());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        DefaultKafkaConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(config);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    NewTopic libraryReservationEventsTopic(IntentBusKafkaProperties properties) {
        // Auto-created by the shared toolCallKafkaAdmin (both require ssuai.kafka.enabled=true).
        return new NewTopic(properties.getTopic(), properties.getPartitions(), (short) 1);
    }

    @Bean
    KafkaLibraryIntentStatusBus kafkaLibraryIntentStatusBus(
            KafkaTemplate<String, String> toolCallKafkaTemplate,
            ConsumerFactory<String, String> intentBusConsumerFactory,
            ObjectMapper objectMapper,
            IntentBusKafkaProperties properties,
            MeterRegistry meterRegistry) {
        return new KafkaLibraryIntentStatusBus(
                toolCallKafkaTemplate,
                intentBusConsumerFactory,
                objectMapper,
                properties,
                meterRegistry);
    }

    private String bootstrapServers() {
        String explicit = environment.getProperty("ssuai.kafka.bootstrap-servers", "");
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        return environment.getProperty("spring.kafka.bootstrap-servers", "");
    }
}
