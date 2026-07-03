package com.kellen.mcp.controller;

import com.kellen.mcp.config.McpOAuthResourceServerProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP OAuth Protected Resource Metadata。
 */
@RestController
@Tag(name = "MCP OAuth Metadata", description = "提供 MCP Protected Resource Metadata")
public class McpOAuthMetadataController {

    private final McpOAuthResourceServerProperties properties;

    public McpOAuthMetadataController(McpOAuthResourceServerProperties properties) {
        this.properties = properties;
    }

    @GetMapping({
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/api/mcp"
    })
    @Operation(summary = "MCP OAuth Protected Resource Metadata", description = "声明 MCP 资源地址、授权服务器和支持的 scope")
    public Map<String, Object> protectedResourceMetadata(HttpServletRequest request) {
        String baseUrl = baseUrl(request);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", endpoint(baseUrl, "/api/mcp"));
        metadata.put("authorization_servers", List.of(authorizationServer(baseUrl)));
        metadata.put("scopes_supported", List.of(properties.readScope(), properties.callScope()));
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_documentation", endpoint(baseUrl, "/v3/api-docs"));
        return metadata;
    }

    private String authorizationServer(String baseUrl) {
        return StringUtils.defaultIfBlank(properties.authorizationServer(), baseUrl);
    }

    private String baseUrl(HttpServletRequest request) {
        if (StringUtils.isNotBlank(properties.externalBaseUrl())) {
            return StringUtils.removeEnd(properties.externalBaseUrl(), "/");
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (StringUtils.isNotBlank(forwardedHost)) {
            String proto = StringUtils.defaultIfBlank(forwardedProto, request.getScheme());
            return proto + "://" + forwardedHost;
        }
        return request.getScheme() + "://" + request.getServerName() + portPart(request.getScheme(), request.getServerPort());
    }

    private String endpoint(String baseUrl, String path) {
        return UriComponentsBuilder.fromUriString(StringUtils.removeEnd(baseUrl, "/"))
                .path(path)
                .build()
                .toUriString();
    }

    private String portPart(String scheme, int port) {
        if (("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443)) {
            return "";
        }
        return ":" + port;
    }
}
