package com.ssuai.domain.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Verifies the public contest surface stays inside PlayMCP's 20-tool limit. */
@ActiveProfiles("test")
@SpringBootTest(properties = "ssuai.mcp.tool-profile=playmcp")
class PlayMcpToolProfileTests {

    private static final Set<String> PLAYMCP_TOOLS = Set.of(
            "start_auth",
            "logout_all",
            "get_my_schedule",
            "get_my_grades",
            "get_my_assignments",
            "get_lms_dashboard",
            "recommend_library_seats",
            "prepare_reserve_library_seat",
            "confirm_action");

    private final ToolCallbackProvider toolCallbackProvider;

    PlayMcpToolProfileTests(@Qualifier("ssuaiMcpTools") ToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @Test
    void exposesNineContestToolsWithCompleteMetadata() {
        ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();

        assertThat(callbacks).hasSize(9);
        assertThat(Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrderElementsOf(PLAYMCP_TOOLS);

        Arrays.stream(callbacks).forEach(callback -> {
            var definition = callback.getToolDefinition();
            assertThat(definition.description()).contains("SSU Campus(숭실대학교 캠퍼스)");
        });
    }
}
