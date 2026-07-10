package com.ssuai.domain.library.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import com.ssuai.domain.library.reservation.intent.KafkaLibraryIntentStatusBus;
import com.ssuai.domain.library.reservation.intent.LibraryIntentStatusBus;

/** The intent bus wiring must prefer the Kafka bus when its bean is present, else fall back safely. */
class LibraryIntentStatusBusSelectionTest {

    private final LibraryRedisConfiguration configuration = new LibraryRedisConfiguration();

    @Test
    @SuppressWarnings("unchecked")
    void prefersKafkaBusWhenPresent() {
        KafkaLibraryIntentStatusBus kafkaBus = mock(KafkaLibraryIntentStatusBus.class);
        ObjectProvider<KafkaLibraryIntentStatusBus> kafkaProvider = mock(ObjectProvider.class);
        when(kafkaProvider.getIfAvailable()).thenReturn(kafkaBus);

        LibraryIntentStatusBus result = configuration.libraryIntentStatusBus(
                kafkaProvider,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new LibraryRedisProperties());

        assertThat(result).isSameAs(kafkaBus);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToNoopWhenNeitherKafkaNorRedissonPresent() {
        ObjectProvider<KafkaLibraryIntentStatusBus> kafkaProvider = mock(ObjectProvider.class);
        when(kafkaProvider.getIfAvailable()).thenReturn(null);
        ObjectProvider<RedissonClient> redissonProvider = mock(ObjectProvider.class);
        when(redissonProvider.getIfAvailable()).thenReturn(null);

        LibraryRedisProperties properties = new LibraryRedisProperties();
        properties.setEnabled(false);

        LibraryIntentStatusBus result = configuration.libraryIntentStatusBus(
                kafkaProvider,
                redissonProvider,
                mock(ObjectProvider.class),
                properties);

        assertThat(result).isNotNull();
        assertThat(result).isNotInstanceOf(KafkaLibraryIntentStatusBus.class);
    }
}
