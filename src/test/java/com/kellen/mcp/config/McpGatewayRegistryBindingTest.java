package com.kellen.mcp.config;

import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointSpec;
import com.alibaba.nacos.api.ai.model.mcp.McpServerBasicInfo;
import com.kellen.mcp.service.McpAgentCardRegistrar;
import com.kellen.mcp.service.McpRegistryPublicationService;
import com.kellen.mcp.service.McpReleaseVersion;
import com.kellen.mcp.service.McpServerRegistrar;
import com.kellen.mcp.service.McpToolCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 真实 Boot 绑定同一个 reuse 网关变量，验证 Server 与工具 Agent 宣告的实际地址。 */
class McpGatewayRegistryBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Binding.class);

    @Test
    void httpsGatewayOverridesInternalRefAndServicePortForBothRegistryAssets() {
        runner.withPropertyValues("custom.gateway-public-base-url=https://gateway.example.invalid:443/",
                "mcp.server.registry.public-base-url=${custom.gateway-public-base-url}",
                "mcp.agent.registry.public-base-url=${custom.gateway-public-base-url}",
                "mcp.server.registry.endpoint-type=REF",
                "mcp.server.registry.advertise-host=internal.example.invalid",
                "mcp.agent.registry.advertise-port=7700").run(context -> {
            var registrar = server(context.getBean(McpServerRegistryProperties.class));
            McpEndpointSpec endpoint = ReflectionTestUtils.invokeMethod(registrar, "buildEndpointSpecification");
            McpServerBasicInfo spec = ReflectionTestUtils.invokeMethod(registrar, "buildServerSpecification", "4.2.7");
            assertThat(endpoint.getType()).isEqualTo("DIRECT");
            assertThat(endpoint.getData()).containsEntry("address", "gateway.example.invalid")
                    .containsEntry("port", "443").containsEntry("transportProtocol", "https");
            assertThat(spec.getRemoteServerConfig().getExportPath()).isEqualTo("/api/mcp");
            assertThat(spec.getRemoteServerConfig().getServiceRef().getTransportProtocol()).isEqualTo("https");
            assertThat(spec.getFrontProtocol()).isEqualTo("streamable-http");
            var agent = new McpAgentCardRegistrar(context.getBean(McpAgentRegistryProperties.class), 7700,
                    mock(McpReleaseVersion.class), mock(McpRegistryPublicationService.class));
            AgentCard card = ReflectionTestUtils.invokeMethod(agent, "buildCard", "ignored.invalid", 7700, "4.2.7");
            assertThat(card.getUrl()).isEqualTo("https://gateway.example.invalid/api/mcp/tools");
            assertThat(card.getSupportedInterfaces()).extracting(i -> i.getUrl()).containsExactly(
                    "https://gateway.example.invalid/api/mcp/tools",
                    "https://gateway.example.invalid/api/mcp/tools/call");
        });
    }

    @Test
    void localGatewayPreservesItsExplicitPort() {
        runner.withPropertyValues("mcp.server.registry.public-base-url=http://localhost:7100",
                "mcp.agent.registry.public-base-url=http://localhost:7100").run(context -> {
            var endpoint = (McpEndpointSpec) ReflectionTestUtils.invokeMethod(
                    server(context.getBean(McpServerRegistryProperties.class)), "buildEndpointSpecification");
            assertThat(endpoint.getData()).containsEntry("address", "localhost").containsEntry("port", "7100")
                    .containsEntry("transportProtocol", "http");
            var registrar = new McpAgentCardRegistrar(context.getBean(McpAgentRegistryProperties.class), 7700,
                    mock(McpReleaseVersion.class), mock(McpRegistryPublicationService.class));
            AgentCard card = ReflectionTestUtils.invokeMethod(registrar, "buildCard", "ignored.invalid", 7700, "4.2.7");
            assertThat(card.getUrl()).isEqualTo("http://localhost:7100/api/mcp/tools");
        });
    }

    @Test
    void invalidGatewayCannotFallBackToInternalInstance() {
        for (String value : new String[]{"ftp://gateway.example.invalid", "https://user:secret@gateway.example.invalid",
                "https://gateway.example.invalid/api/mcp", "https://gateway.example.invalid?token=secret",
                "https://gateway.example.invalid#fragment", "https://gateway.example.invalid:0"}) {
            runner.withPropertyValues("mcp.server.registry.public-base-url=" + value).run(context ->
                    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                            server(context.getBean(McpServerRegistryProperties.class)), "buildEndpointSpecification"))
                            .hasMessage("MCP_REGISTRY_INVALID_PUBLIC_BASE_URL"));
        }
    }

    @Test
    void absentPublicBaseKeepsLegacyEndpointType() {
        runner.run(context -> {
            var endpoint = (McpEndpointSpec) ReflectionTestUtils.invokeMethod(
                    server(context.getBean(McpServerRegistryProperties.class)), "buildEndpointSpecification");
            assertThat(endpoint.getType()).isEqualTo("REF");
        });
    }

    private static McpServerRegistrar server(McpServerRegistryProperties properties) {
        return new McpServerRegistrar(properties, mock(McpToolCatalogService.class), 7700,
                mock(McpReleaseVersion.class), mock(McpRegistryPublicationService.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({McpServerRegistryProperties.class, McpAgentRegistryProperties.class})
    static class Binding { }
}
