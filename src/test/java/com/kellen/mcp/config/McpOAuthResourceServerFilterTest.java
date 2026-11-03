package com.kellen.mcp.config;

import com.kellen.utils.auth.JwtUtils;
import com.kellen.utils.auth.JwtKeyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.env.MockEnvironment;
import com.kellen.mcp.security.McpVerifiedCaller;
import jakarta.servlet.ServletException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOAuthResourceServerFilterTest {

    private final McpOAuthResourceServerProperties properties = new McpOAuthResourceServerProperties(
            true,
            "http://localhost:7100",
            "http://localhost:7100/api/mcp",
            "http://localhost:7100",
            "http://localhost:7100",
            "mcp.tools.read",
            "mcp.tools.call"
    );

    @BeforeEach
    void configureSyntheticJwtKey() {
        String key = java.util.Base64.getEncoder().encodeToString(
                "synthetic-mcp-unit-test-key-32bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        new JwtKeyConfig(new MockEnvironment().withProperty("security.jwt.secret", key));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        com.kellen.security.UserContextHolder.clear();
        com.kellen.utils.context.TenantContextHolder.clear();
    }

    @Test
    void validTokenMapsScopesToMcpAuthorities() throws Exception {
        McpOAuthResourceServerFilter filter = new McpOAuthResourceServerFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader("Authorization", "Bearer " + token("http://localhost:7100/api/mcp", "mcp.tools.read mcp.tools.call"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authentication = new AtomicReference<>();
        FilterChain chain = (req, res) -> authentication.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authentication.get()).isNotNull();
        assertThat(authentication.get().getAuthorities()).extracting("authority")
                .contains("mcp:tool:list", "mcp:tool:call");
    }

    @Test
    void invalidAudienceIsRejected() throws Exception {
        McpOAuthResourceServerFilter filter = new McpOAuthResourceServerFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader("Authorization", "Bearer " + token("other", "mcp.tools.read"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .contains("Bearer")
                .contains("resource_metadata=\"http://localhost:7100/.well-known/oauth-protected-resource\"")
                .contains("error=\"invalid_token\"");
    }

    @Test
    void missingTokenReturnsBearerChallenge() throws Exception {
        McpOAuthResourceServerFilter filter = new McpOAuthResourceServerFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "gateway.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .contains("Bearer")
                .contains("resource_metadata=\"http://localhost:7100/.well-known/oauth-protected-resource\"")
                .contains("scope=\"mcp.tools.read mcp.tools.call\"")
                .doesNotContain("error=\"invalid_token\"");
    }

    @Test
    void externalBaseUrlOverridesRequestHostInBearerChallenge() throws Exception {
        McpOAuthResourceServerProperties externalProperties = new McpOAuthResourceServerProperties(
                true,
                "http://localhost:7100",
                "http://localhost:7100/api/mcp",
                "https://auth.example.com",
                "https://gateway.example.com/",
                "mcp.tools.read",
                "mcp.tools.call"
        );
        McpOAuthResourceServerFilter filter = new McpOAuthResourceServerFilter(externalProperties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.setServerName("192.168.10.56");
        request.setServerPort(7700);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .contains("resource_metadata=\"https://gateway.example.com/.well-known/oauth-protected-resource\"");
    }

    @Test
    void verifiedCallerCannotBeInjectedAndIsClearedAfterTheRequest() throws Exception {
        McpOAuthResourceServerFilter filter = new McpOAuthResourceServerFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader("Authorization", "Bearer " + token("http://localhost:7100/api/mcp", "mcp.tools.call"));
        request.setAttribute(McpVerifiedCaller.REQUEST_ATTRIBUTE, "untrusted caller");
        AtomicReference<McpVerifiedCaller> caller = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                caller.set((McpVerifiedCaller) req.getAttribute(McpVerifiedCaller.REQUEST_ATTRIBUTE)));

        assertThat(caller.get().clientId()).isEqualTo("mcp-client");
        assertThat(caller.get().scopes()).containsExactly("mcp.tools.call");
        assertThat(request.getAttribute(McpVerifiedCaller.REQUEST_ATTRIBUTE)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void connectorExceptionIsNotRewrittenAsOAuthFailureAndStillClearsIdentity() {
        McpOAuthResourceServerFilter filter = new McpOAuthResourceServerFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader("Authorization", "Bearer " + token("http://localhost:7100/api/mcp", "mcp.tools.call"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("synthetic connector failure");
        })).isInstanceOf(ServletException.class).hasMessage("synthetic connector failure");

        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(McpVerifiedCaller.REQUEST_ATTRIBUTE)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenRemovesStaleRequestIdentityAndAuthentication() throws Exception {
        McpOAuthResourceServerFilter filter = new McpOAuthResourceServerFilter(properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mcp");
        request.setAttribute(McpVerifiedCaller.REQUEST_ATTRIBUTE, "untrusted caller");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("unauthorized request reached the connector");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute(McpVerifiedCaller.REQUEST_ATTRIBUTE)).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void oauthPrincipalTenantCannotBeOverriddenByHttpHeader() throws Exception {
        var tenant = new com.kellen.security.config.TenantProperties();
        tenant.setEnabled(true);
        var security = new com.kellen.security.config.SecurityAuthProperties();
        security.setEnabled(true);
        var interceptor = new com.kellen.config.web.ReqInterceptor(tenant,
                new com.kellen.traffic.TrafficGovernanceProperties(), security);
        var request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader("Authorization", "Bearer " + token("http://localhost:7100/api/mcp", "mcp.tools.call"));
        request.addHeader("X-Tenant-Id", "forged-other-tenant");
        var response = new MockHttpServletResponse();
        AtomicReference<String> observedTenant = new AtomicReference<>();

        new McpOAuthResourceServerFilter(properties).doFilter(request, response, (req, res) -> {
            try {
                assertThat(interceptor.preHandle(request, response, null)).isTrue();
                observedTenant.set(com.kellen.utils.context.TenantContextHolder.getTenantId());
                interceptor.afterCompletion(request, response, null, null);
            } catch (Exception exception) { throw new ServletException(exception); }
        });

        assertThat(observedTenant.get()).isEqualTo("1");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(com.kellen.utils.context.TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    void oauthIdentityWithoutTenantCannotUseHttpHeaderToInventOne() throws Exception {
        var tenant = new com.kellen.security.config.TenantProperties();
        tenant.setEnabled(true);
        var security = new com.kellen.security.config.SecurityAuthProperties();
        security.setEnabled(true);
        var interceptor = new com.kellen.config.web.ReqInterceptor(tenant,
                new com.kellen.traffic.TrafficGovernanceProperties(), security);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", "client:mcp-client");
        claims.put("client_id", "mcp-client");
        claims.put("scope", "mcp.tools.call");
        claims.put("aud", "http://localhost:7100/api/mcp");
        var request = new MockHttpServletRequest("POST", "/api/mcp");
        request.addHeader("Authorization", "Bearer " + JwtUtils.createJwt("oauth-no-tenant", "client:mcp-client", claims,
                60_000L, "http://localhost:7100"));
        request.addHeader("X-Tenant-Id", "forged-tenant");
        var response = new MockHttpServletResponse();
        new McpOAuthResourceServerFilter(properties).doFilter(request, response, (req, res) -> {
            try { assertThat(interceptor.preHandle(request, response, null)).isFalse(); }
            catch (Exception exception) { throw new ServletException(exception); }
        });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(com.kellen.utils.context.TenantContextHolder.getTenantId()).isNull();
    }

    private String token(String audience, String scope) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("userId", "client:mcp-client");
        claims.put("username", "mcp-client");
        claims.put("tenantId", "1");
        claims.put("client_id", "mcp-client");
        claims.put("scope", scope);
        claims.put("aud", audience);
        return JwtUtils.createJwt("oauth-token", "client:mcp-client", claims, 60_000L, "http://localhost:7100");
    }
}
