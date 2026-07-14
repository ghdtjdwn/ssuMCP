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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ssuai.domain.library.auth.LibraryCredentialLoginService;
import com.ssuai.global.exception.LibraryAuthRequiredException;

@ActiveProfiles("test")
@WebMvcTest(McpLibraryAuthController.class)
@TestPropertySource(properties = {
        "ssuai.frontend.origin=https://ssuai.example",
})
class McpLibraryAuthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private McpAuthService mcpAuthService;

    @MockitoBean
    private LibraryCredentialLoginService credentialLoginService;

    @MockitoBean
    private McpProviderCredentialService credentialService;

    private static final McpAuthSessionId SESSION_ID = new McpAuthSessionId("test-session-library");
    private static final Instant EXPIRES = Instant.parse("2026-05-18T11:00:00Z");

    @BeforeEach
    void liveOwner() {
        when(mcpAuthService.find(SESSION_ID.value())).thenReturn(Optional.of(
                new McpAuthSession(SESSION_ID, Instant.EPOCH, EXPIRES, java.util.Map.of())));
        when(mcpAuthService.linkProviderIfCurrentAttempt(
                org.mockito.ArgumentMatchers.eq(SESSION_ID),
                org.mockito.ArgumentMatchers.eq(McpProviderType.LIBRARY),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    // --- GET /start ---

    @Test
    void startRedirectsToFrontendLoginPage() throws Exception {
        mockMvc.perform(get("/api/mcp/auth/library/start").param("state", "somestate"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith("https://ssuai.example/mcp/auth/library?state=")));
    }

    @Test
    void startEncodesStateInRedirect() throws Exception {
        mockMvc.perform(get("/api/mcp/auth/library/start").param("state", "a b+c"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(" "))));
    }

    // --- POST /callback ---

    @Test
    void callbackWithInvalidStateReturns400() throws Exception {
        when(mcpAuthService.consumeState("bad-state")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/mcp/auth/library/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"bad-state\",\"loginId\":\"20231234\",\"password\":\"enc-pw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));

        verify(credentialLoginService, never()).login(any(), any());
        verify(mcpAuthService).consumeState("bad-state");
    }

    @Test
    void callbackWithProviderMismatchReturns400() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("state", SESSION_ID, McpProviderType.SAINT, EXPIRES, 1L);
        when(mcpAuthService.consumeState("state")).thenReturn(Optional.of(entry));

        mockMvc.perform(post("/api/mcp/auth/library/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"state\",\"loginId\":\"20231234\",\"password\":\"enc-pw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_MISMATCH"));

        verify(credentialLoginService, never()).login(any(), any());
        verify(mcpAuthService).consumeState("state");
    }

    @Test
    void callbackAuthFailureReturns401AfterOneTimeStateIsConsumed() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("state", SESSION_ID, McpProviderType.LIBRARY, EXPIRES, 1L);
        when(mcpAuthService.consumeState("state")).thenReturn(Optional.of(entry));
        doThrow(new LibraryAuthRequiredException()).when(credentialLoginService).login(any(), any());

        mockMvc.perform(post("/api/mcp/auth/library/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"state\",\"loginId\":\"20231234\",\"password\":\"wrong-pw\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_FAILED"));

        verify(mcpAuthService).consumeState("state");
        verify(mcpAuthService, never()).linkProviderIfCurrentAttempt(
                any(), any(), any(), anyLong());
    }

    @Test
    void callbackSuccessConsumesStateLinksLibraryProviderAndReturns200() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("state", SESSION_ID, McpProviderType.LIBRARY, EXPIRES, 1L);
        when(mcpAuthService.consumeState("state")).thenReturn(Optional.of(entry));

        mockMvc.perform(post("/api/mcp/auth/library/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"state\",\"loginId\":\"20231234\",\"password\":\"enc-pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(credentialLoginService).login(any(), any());
        verify(mcpAuthService).consumeState("state");
        verify(mcpAuthService).linkProviderIfCurrentAttempt(
                eq(SESSION_ID), eq(McpProviderType.LIBRARY), anyString(), eq(1L));
    }

    @Test
    void callbackSuccessResponseDoesNotContainLoginId() throws Exception {
        McpAuthStateEntry entry = new McpAuthStateEntry("state", SESSION_ID, McpProviderType.LIBRARY, EXPIRES, 1L);
        when(mcpAuthService.consumeState("state")).thenReturn(Optional.of(entry));

        mockMvc.perform(post("/api/mcp/auth/library/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"state\",\"loginId\":\"20231234\",\"password\":\"enc-pw\"}"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assert !body.contains("20231234") : "Response must not contain loginId: " + body;
                });
    }

    @Test
    void callbackMissingFieldsReturns400() throws Exception {
        mockMvc.perform(post("/api/mcp/auth/library/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"state\"}"))
                .andExpect(status().isBadRequest());
    }
}
