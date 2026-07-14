package com.ssuai.domain.auth.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.auth.lms.LmsSsoService;
import com.ssuai.global.exception.LmsAuthFailedException;

@ActiveProfiles("test")
@WebMvcTest(McpLmsAuthController.class)
@Import({AuthProperties.class, McpAuthUrlFactory.class})
@TestPropertySource(properties = {
        "ssuai.auth.api-base-url=https://api.ssuai.test",
        "ssuai.auth.smartid-sso-url=https://smartid.example/sso",
})
class McpLmsAuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LmsSsoService lmsSsoService;

    @MockitoBean
    private McpAuthService mcpAuthService;

    @MockitoBean
    private McpProviderCredentialService credentialService;

    private static final McpAuthSessionId SESSION_ID = new McpAuthSessionId("test-session-lms");
    private static final Instant EXPIRES = Instant.parse("2026-05-18T11:00:00Z");

    @BeforeEach
    void liveOwner() {
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.of(
                new McpAuthSession(SESSION_ID, Instant.EPOCH, EXPIRES, java.util.Map.of())));
        when(mcpAuthService.linkProviderIfCurrentAttempt(
                org.mockito.ArgumentMatchers.eq(SESSION_ID),
                org.mockito.ArgumentMatchers.eq(McpProviderType.LMS),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    @Test
    void startRedirectsToSmartId() throws Exception {
        mockMvc.perform(get("/api/mcp/auth/lms/start").param("state", "somestate"))
                .andExpect(status().isFound())
                .andExpect(result -> {
                    String location = result.getResponse().getHeader("Location");
                    assert location != null;
                    assert location.startsWith("https://smartid.example/sso?apiReturnUrl=");
                    assert location.contains("lms%2Fcallback");
                });
    }

    @Test
    void callbackWithMissingStateReturnsErrorPage() throws Exception {
        when(mcpAuthService.consumeState(null)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/mcp/auth/lms/callback"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(lmsSsoService, never()).authenticateForSession(any(), any(), any());
    }

    @Test
    void callbackWithExpiredStateReturnsErrorPage() throws Exception {
        when(mcpAuthService.consumeState("expired")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "t").param("sIdno", "s").param("state", "expired"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(lmsSsoService, never()).authenticateForSession(any(), any(), any());
    }

    @Test
    void doubleQuestionMarkStateIsHandled() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sIdno", "20231234")
                        .param("state", "valid-state?sToken=tok"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(mcpAuthService).consumeState(stateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(stateCaptor.getValue()).isEqualTo("valid-state");
        verify(lmsSsoService).authenticateForSession(eq("tok"), eq("20231234"), anyString());
        verify(mcpAuthService).linkProviderIfCurrentAttempt(
                eq(SESSION_ID), eq(McpProviderType.LMS), anyString(), eq(1L));
    }

    @Test
    void doubleQuestionMarkStateWithIdnoAndTokenIsHandled() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("state", "valid-state?sIdno=20231234&sToken=tok"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(mcpAuthService).consumeState(stateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(stateCaptor.getValue()).isEqualTo("valid-state");
        verify(lmsSsoService).authenticateForSession(eq("tok"), eq("20231234"), anyString());
        verify(mcpAuthService).linkProviderIfCurrentAttempt(
                eq(SESSION_ID), eq(McpProviderType.LMS), anyString(), eq(1L));
    }

    @Test
    void normalStateIsHandled() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("normal-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("normal-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "tok").param("sIdno", "20231234").param("state", "normal-state"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        verify(mcpAuthService).consumeState(stateCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(stateCaptor.getValue()).isEqualTo("normal-state");
        verify(lmsSsoService).authenticateForSession(eq("tok"), eq("20231234"), anyString());
    }

    @Test
    void callbackWithProviderMismatchReturnsErrorPage() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.SAINT, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "t").param("sIdno", "20231234").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(lmsSsoService, never()).authenticateForSession(any(), any(), any());
    }

    @Test
    void callbackWithMissingSIdnoReturnsErrorPage() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "tok").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(lmsSsoService, never()).authenticateForSession(any(), any(), any());
    }

    @Test
    void callbackWithBlankSIdnoReturnsErrorPage() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "tok").param("sIdno", "  ").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")));

        verify(lmsSsoService, never()).authenticateForSession(any(), any(), any());
        verify(mcpAuthService, never()).linkProviderIfCurrentAttempt(
                any(), any(), any(), anyLong());
    }

    @Test
    void callbackAuthFailureReturnsErrorPageWithoutLinking() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));
        doThrow(new LmsAuthFailedException("bad token"))
                .when(lmsSsoService).authenticateForSession(any(), any(), any());

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "bad").param("sIdno", "20231234").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 실패")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("bad token"))));

        verify(mcpAuthService, never()).linkProviderIfCurrentAttempt(
                any(), any(), any(), anyLong());
    }

    @Test
    void callbackSuccessLinksProviderWithTrimmedSIdno() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "tok").param("sIdno", " 20231234 ").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 완료")));

        verify(mcpAuthService).linkProviderIfCurrentAttempt(
                eq(SESSION_ID), eq(McpProviderType.LMS), anyString(), eq(1L));
    }

    @Test
    void callbackSuccessPageDoesNotContainStudentId() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("valid-state", SESSION_ID, McpProviderType.LMS, EXPIRES, 1L);
        when(mcpAuthService.consumeState("valid-state")).thenReturn(Optional.of(entry));

        mockMvc.perform(get("/api/mcp/auth/lms/callback")
                        .param("sToken", "tok").param("sIdno", "20231234").param("state", "valid-state"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("20231234"))));
    }
}
