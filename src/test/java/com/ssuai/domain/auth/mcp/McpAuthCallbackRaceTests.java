package com.ssuai.domain.auth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ssuai.domain.auth.lms.LmsSsoService;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpAuthCallbackRaceTests {

    @Autowired private McpAuthService authService;
    @Autowired private McpSessionRevocationService revocationService;
    @Autowired private McpLmsAuthController controller;
    @Autowired private McpSessionRepository sessionRepository;
    @Autowired private McpAuthStateRepository stateRepository;

    @MockitoBean private LmsSsoService lmsSsoService;

    @BeforeEach
    void clean() {
        stateRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    @Test
    void logoutDuringUpstreamAuthenticationPreventsCallbackFromRelinking() throws Exception {
        McpAuthSession session = authService.createSession();
        McpAuthStateEntry state = authService.generateState(session.id(), McpProviderType.LMS);
        CountDownLatch authenticationStarted = new CountDownLatch(1);
        CountDownLatch allowAuthenticationToReturn = new CountDownLatch(1);
        doAnswer(invocation -> {
            authenticationStarted.countDown();
            if (!allowAuthenticationToReturn.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release authentication");
            }
            return null;
        }).when(lmsSsoService).authenticateForSession(
                anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<String>> callback = executor.submit(() -> controller.callback(
                    "synthetic-token", "upstream-user", state.state()));
            assertThat(authenticationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            revocationService.revokeProvider(
                    authService.find(session.id().value()).orElseThrow(), McpProviderType.LMS);
            allowAuthenticationToReturn.countDown();

            ResponseEntity<String> response = callback.get(5, TimeUnit.SECONDS);
            assertThat(response.getBody()).contains("로그인 실패", "취소");
            assertThat(authService.find(session.id().value()).orElseThrow()
                    .isLinked(McpProviderType.LMS)).isFalse();

            ArgumentCaptor<String> credentialKey = ArgumentCaptor.forClass(String.class);
            verify(lmsSsoService).authenticateForSession(
                    anyString(), anyString(), credentialKey.capture());
            assertThat(credentialKey.getValue())
                    .isNotEqualTo(session.id().value())
                    .hasSize(32);
        } finally {
            allowAuthenticationToReturn.countDown();
            executor.shutdownNow();
        }
    }
}
