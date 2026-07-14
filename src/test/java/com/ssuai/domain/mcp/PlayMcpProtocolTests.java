package com.ssuai.domain.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** Exercises the exact Streamable HTTP surface that PlayMCP imports. */
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ssuai.mcp.tool-profile=playmcp")
class PlayMcpProtocolTests {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "get_my_schedule",
            "get_my_grades",
            "get_my_assignments",
            "get_lms_dashboard",
            "recommend_library_seats");

    @LocalServerPort
    private int serverPort;

    @Test
    void streamableHttpListsOnlyContestToolsWithRequiredAnnotations() {
        try (McpSyncClient client = openClient()) {
            client.initialize();
            List<McpSchema.Tool> tools = client.listTools().tools();

            assertThat(tools).hasSize(9);
            assertThat(tools).allSatisfy(tool -> {
                assertThat(tool.annotations()).isNotNull();
                assertThat(tool.annotations().title()).isNotBlank();
                assertThat(tool.annotations().openWorldHint()).isTrue();
                if (READ_ONLY_TOOLS.contains(tool.name())) {
                    assertThat(tool.annotations().readOnlyHint()).isTrue();
                    assertThat(tool.annotations().destructiveHint()).isFalse();
                    assertThat(tool.annotations().idempotentHint()).isTrue();
                }
            });
        }
    }

    private McpSyncClient openClient() {
        return McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + serverPort)
                .connectTimeout(Duration.ofSeconds(5))
                .build())
                .requestTimeout(Duration.ofSeconds(10))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
    }
}
