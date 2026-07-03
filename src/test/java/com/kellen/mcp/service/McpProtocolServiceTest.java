package com.kellen.mcp.service;

import com.kellen.mcp.config.McpServerRegistryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpProtocolServiceTest {

    private final McpProtocolService service = new McpProtocolService(
            new McpToolCatalogService(),
            new McpServerRegistryProperties(
                    false,
                    "",
                    "",
                    "",
                    "",
                    "/nacos",
                    "mcp",
                    "1.0.2",
                    "",
                    "mcp-streamable",
                    "/api/mcp",
                    "REF",
                    "mcp",
                    "DEFAULT_GROUP",
                    "",
                    0
            )
    );

    @BeforeEach
    void setUpSecurityContext() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "tester",
                "n/a",
                "mcp:tool:list",
                "mcp:tool:call"
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void initializeEchoesClientProtocolVersion() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of("protocolVersion", "2024-11-05")
        )).orElseThrow();

        assertThat(response).containsEntry("id", 1);
        assertThat(result(response)).containsEntry("protocolVersion", "2024-11-05");
    }

    @Test
    void toolsListReturnsRegisteredTools() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/list"
        )).orElseThrow();

        assertThat(result(response).get("tools").toString()).contains("third-party.health-check");
        assertThat(result(response).get("tools").toString()).contains("user-profile.tags-by-wechat-name");
    }

    @Test
    void toolsCallDelegatesToToolCatalog() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 3,
                "method", "tools/call",
                "params", Map.of(
                        "name", "third-party.health-check",
                        "arguments", Map.of("systemCode", "demo")
                )
        )).orElseThrow();

        assertThat(result(response)).containsEntry("isError", false);
        assertThat(result(response).get("structuredContent").toString()).contains("connectorReady=false");
    }

    @Test
    void toolsCallCanReturnUserTagsByWechatName() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 5,
                "method", "tools/call",
                "params", Map.of(
                        "name", "user-profile.tags-by-wechat-name",
                        "arguments", Map.of(
                                "wechatUserName", "Autumn",
                                "tenantId", "100"
                        )
                )
        )).orElseThrow();

        assertThat(result(response)).containsEntry("isError", false);
        assertThat(result(response).get("structuredContent").toString())
                .contains("Autumn", "语聚测试用户", "健康管理关注", "confidence=100", "lookupReady=false");
    }

    @Test
    void toolsCallRequiresCallAuthority() {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(
                "tester",
                "n/a",
                "mcp:tool:list"
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThatThrownBy(() -> service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 4,
                "method", "tools/call",
                "params", Map.of("name", "third-party.health-check")
        ))).isInstanceOf(AccessDeniedException.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }
}
