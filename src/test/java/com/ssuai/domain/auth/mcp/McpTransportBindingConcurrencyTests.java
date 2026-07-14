package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpTransportBindingConcurrencyTests {

    @Autowired
    private McpAuthSessionStore store;

    @Autowired
    private McpSessionRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void concurrentClientsCannotOverwriteOrShareOneTransportBinding() throws Exception {
        McpAuthSession first = store.create();
        McpAuthSession second = store.create();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> contenders = List.of(
                    () -> bindAfter(start, first.id()),
                    () -> bindAfter(start, second.id()));
            List<Future<Boolean>> results = contenders.stream()
                    .map(executor::submit)
                    .toList();
            start.countDown();

            int winners = 0;
            for (Future<Boolean> result : results) {
                try {
                    if (result.get()) {
                        winners++;
                    }
                } catch (java.util.concurrent.ExecutionException conflict) {
                    // The database uniqueness constraint is the final multi-node CAS guard.
                    assertThat(conflict.getCause()).isInstanceOf(RuntimeException.class);
                }
            }

            assertThat(winners).isEqualTo(1);
            McpAuthSession bound = store.findByTransportId("shared-transport").orElseThrow();
            assertThat(bound.id()).isIn(first.id(), second.id());
            long rows = repository.findAll().stream()
                    .filter(entity -> "shared-transport".equals(entity.getTransportSessionId()))
                    .count();
            assertThat(rows).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean bindAfter(CountDownLatch start, McpAuthSessionId sessionId)
            throws InterruptedException {
        start.await();
        return store.bindTransportId(sessionId, "shared-transport");
    }
}
