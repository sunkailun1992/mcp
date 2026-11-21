package com.kellen.mcp.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kellen.mcp.controller.McpToolController;
import com.kellen.mcp.service.McpToolCatalogService;
import com.kellen.mcp.support.FakeIssuer;
import com.kellen.security.SecurityUser;
import com.kellen.security.oauth2.FleetResourceServerAutoConfiguration;
import com.kellen.security.oauth2.SecurityContextResult;
import com.kellen.security.oauth2.SecurityContextRpcService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * mcp 是标准资源服务器（utils）：只收 Keycloak 签给受众 github-mcp 的 RS256 令牌，并按 RFC 9728 发布受保护资源元数据，
 * 把 Keycloak 签发方声明为授权服务器、在 401 质询里给出元数据地址，MCP 客户端据此发现去哪里取令牌（MCP 授权规范）。
 * 真实 Tomcat、真实资源服务器与 Boot 的 issuer-uri 解码器；签发方与 user 服务的业务上下文是本地替身。
 */
@SpringBootTest(classes = McpProtectedResourceMetadataTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=mcp-resource-server-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.jws-algorithms=RS256",
                "spring.security.oauth2.resourceserver.jwt.audiences=github-mcp"})
class McpProtectedResourceMetadataTest {

    static final FakeIssuer ISSUER = FakeIssuer.start();

    @LocalServerPort
    int port;

    @MockitoBean
    McpToolCatalogService catalog;

    final HttpClient http = HttpClient.newHttpClient();

    final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
    }

    @AfterAll
    static void stop() {
        ISSUER.close();
    }

    @Test
    void metadataListsKeycloakAsAuthorizationServer() throws Exception {
        // RFC 9728 §3.1：资源路径插在 well-known 之后；资源标识就是 MCP 入口地址。
        HttpResponse<String> forMcp = get("/.well-known/oauth-protected-resource/api/mcp", null);
        assertThat(forMcp.statusCode()).isEqualTo(200);
        JsonNode metadata = mapper.readTree(forMcp.body());
        assertThat(metadata.path("resource").asText()).isEqualTo(base() + "/api/mcp");
        assertThat(metadata.path("authorization_servers")).hasSize(1);
        assertThat(metadata.path("authorization_servers").get(0).asText()).isEqualTo(ISSUER.issuer());
        assertThat(metadata.path("bearer_methods_supported").get(0).asText()).isEqualTo("header");
        // 401 质询指向的站点级元数据：资源标识为站点本身，授权服务器同样是 Keycloak。
        JsonNode root = mapper.readTree(get("/.well-known/oauth-protected-resource", null).body());
        assertThat(root.path("resource").asText()).isEqualTo(base());
        assertThat(root.path("authorization_servers").get(0).asText()).isEqualTo(ISSUER.issuer());
    }

    @Test
    void unauthenticatedResponseHasResourceMetadataInWwwAuthenticate() throws Exception {
        HttpResponse<String> response = get("/api/mcp/tools", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(challenge -> assertThat(challenge)
                .startsWith("Bearer").contains("resource_metadata=\"" + base() + "/.well-known/oauth-protected-resource\""));
        verifyNoInteractions(catalog);
    }

    @Test
    void tokenWithAudienceGithubMcpAccepted() throws Exception {
        when(catalog.listTools()).thenReturn(List.of());
        HttpResponse<String> response = get("/api/mcp/tools", ISSUER.token("user-a", "github-mcp", "github-gateway"));
        assertThat(response.statusCode()).isEqualTo(200);
        verify(catalog).listTools();
    }

    @Test
    void tokenWithAudienceGithubApiRejected() throws Exception {
        // 网关转给业务后端的用户令牌受众是 github-api，不能拿来调 MCP。
        HttpResponse<String> response = get("/api/mcp/tools", ISSUER.token("user-a", "github-api", "github-gateway"));
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(challenge -> assertThat(challenge)
                .contains("error=\"invalid_token\""));
        verifyNoInteractions(catalog);
    }

    @Test
    void userWithoutToolListPermissionIs403() throws Exception {
        // 受众正确只说明令牌是给 mcp 的；能不能列工具仍看 user 服务给的权限码。
        HttpResponse<String> response = get("/api/mcp/tools", ISSUER.token("user-no-mcp", "github-mcp", "github-gateway"));
        assertThat(response.statusCode()).isEqualTo(403);
        verifyNoInteractions(catalog);
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 测试上下文：真实 Spring Security、utils 资源服务器与 Boot 的 issuer-uri 解码器、真实工具目录控制器；
     * user 服务的业务上下文是替身（user-a 有 MCP 两个权限码，user-no-mcp 没有）。
     */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class,
            SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
            FleetResourceServerAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class})
    @Import(McpToolController.class)
    static class Config {

        @Bean
        SecurityContextRpcService securityContextRpcService() {
            return (userId, requestedTenantId) -> {
                List<String> authorities = switch (userId) {
                    case "user-a" -> List.of("mcp:tool:list", "mcp:tool:call");
                    case "user-no-mcp" -> List.of("rag:document:manage");
                    default -> null;
                };
                if (authorities == null) {
                    return SecurityContextResult.of(SecurityContextResult.Status.USER_NOT_FOUND);
                }
                return SecurityContextResult.ok(SecurityUser.builder().userId(userId).username(userId).tenantId("tenant-a")
                        .deptId("dept-a").dataScope("SELF").dataScopeDeptIds(List.of()).authorities(authorities)
                        .enabled(true).tenantIds(List.of("tenant-a")).build());
            };
        }
    }
}
