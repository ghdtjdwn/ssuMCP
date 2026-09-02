package com.ssuai.domain.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "get_my_schedule",
            "get_my_grades",
            "get_my_assignments",
            "get_lms_dashboard",
            "recommend_library_seats");

    @LocalServerPort
    private int serverPort;

    @Test
    void streamableHttpListsOnlyProfileToolsWithRequiredAnnotations() {
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

    @Test
    void initializeRemainsSynchronousJsonResponse() throws Exception {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        URI endpoint = URI.create("http://localhost:" + serverPort + "/mcp");
        HttpRequest initialize = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json,text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                          "protocolVersion":"2025-03-26",
                          "capabilities":{},
                          "clientInfo":{"name":"sync-initialize-test","version":"1.0"}
                        }}
                        """))
                .build();

        HttpResponse<String> response = httpClient.send(
                initialize, HttpResponse.BodyHandlers.ofString());
        String sessionId = response.headers().firstValue("Mcp-Session-Id").orElse(null);
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("application/json");
            assertThat(sessionId).isNotBlank();
            assertThat(OBJECT_MAPPER.readTree(response.body())
                    .path("result").path("protocolVersion").asText())
                    .isEqualTo("2025-03-26");
        } finally {
            if (sessionId != null) {
                HttpRequest delete = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(5))
                        .header("Mcp-Session-Id", sessionId)
                        .DELETE()
                        .build();
                httpClient.send(delete, HttpResponse.BodyHandlers.discarding());
            }
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
