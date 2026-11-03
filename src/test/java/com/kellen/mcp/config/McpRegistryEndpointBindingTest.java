package com.kellen.mcp.config;

import com.alibaba.nacos.api.ai.model.mcp.McpEndpointSpec;
import com.alibaba.nacos.api.ai.model.mcp.McpServerBasicInfo;
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

/** Boot 绑定真实配置属性及发布组装，隔离 Nacos/Redis，不访问任何外部地址。 */
class McpRegistryEndpointBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfiguration.class);

    @Test
    void existingConfigurationWithoutSchemeDefaultsToHttp() {
        runner.withPropertyValues("mcp.server.registry.version=1.0.2").run(context -> {
            var properties = context.getBean(McpServerRegistryProperties.class);
            assertThat(properties.version()).isEqualTo("1.0.2");
            assertThat(properties.transportProtocol()).isEqualTo("http");
            McpEndpointSpec endpoint = ReflectionTestUtils.invokeMethod(registrar(properties), "buildEndpointSpecification");
            assertThat(endpoint.getType()).isEqualTo("REF");
            assertThat(endpoint.getData()).containsEntry("transportProtocol", "http");
        });
    }

    @Test
    void reuseReferenceBindsHttpsForRefWithoutChangingStreamableHttpTransport() {
        runner.withPropertyValues("custom.mcp-backend-scheme=https",
                "mcp.server.registry.transport-protocol=${custom.mcp-backend-scheme}",
                "mcp.server.registry.namespace=synthetic-space").run(context -> {
            var registrar = registrar(context.getBean(McpServerRegistryProperties.class));
            McpEndpointSpec endpoint = ReflectionTestUtils.invokeMethod(registrar, "buildEndpointSpecification");
            McpServerBasicInfo server = ReflectionTestUtils.invokeMethod(registrar, "buildServerSpecification", "4.2.7");
            assertThat(endpoint.getData()).containsEntry("transportProtocol", "https");
            assertThat(server.getRemoteServerConfig().getServiceRef().getTransportProtocol()).isEqualTo("https");
            assertThat(server.getFrontProtocol()).isEqualTo("streamable-http");
            assertThat(server.getProtocol()).isEqualTo("mcp-streamable");
        });
    }

    @Test
    void directEndpointBindsItsOwnHostPortAndSchemeFromReuseReferences() {
        runner.withPropertyValues("custom.mcp-backend-scheme=https",
                "custom.mcp-backend-host=mcp.internal.example.invalid", "custom.mcp-backend-port=8443",
                "mcp.server.registry.endpoint-type=DIRECT",
                "mcp.server.registry.transport-protocol=${custom.mcp-backend-scheme}",
                "mcp.server.registry.advertise-host=${custom.mcp-backend-host}",
                "mcp.server.registry.advertise-port=${custom.mcp-backend-port}").run(context -> {
            var registrar = registrar(context.getBean(McpServerRegistryProperties.class));
            McpEndpointSpec endpoint = ReflectionTestUtils.invokeMethod(registrar, "buildEndpointSpecification");
            McpServerBasicInfo server = ReflectionTestUtils.invokeMethod(registrar, "buildServerSpecification", "4.2.7");
            assertThat(endpoint.getData()).containsEntry("transportProtocol", "https")
                    .containsEntry("address", "mcp.internal.example.invalid").containsEntry("port", "8443");
            assertThat(server.getRemoteServerConfig().getServiceRef().getTransportProtocol()).isEqualTo("https");
        });
    }

    @Test
    void mcpTransportNameCannotBeUsedAsEndpointUrlScheme() {
        runner.withPropertyValues("mcp.server.registry.transport-protocol=streamable-http").run(context -> {
            var registrar = registrar(context.getBean(McpServerRegistryProperties.class));
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(registrar, "buildEndpointSpecification"))
                    .hasMessage("MCP_REGISTRY_INVALID_TRANSPORT_PROTOCOL");
        });
    }

    private static McpServerRegistrar registrar(McpServerRegistryProperties properties) {
        return new McpServerRegistrar(properties, mock(McpToolCatalogService.class), 7700,
                mock(McpReleaseVersion.class), mock(McpRegistryPublicationService.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpServerRegistryProperties.class)
    static class BindingConfiguration { }
}
