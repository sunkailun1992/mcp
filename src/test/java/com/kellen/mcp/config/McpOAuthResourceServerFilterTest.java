package com.kellen.mcp.config;

import com.kellen.utils.auth.JwtUtils;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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
