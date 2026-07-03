package com.kellen.mcp.service;

import com.kellen.mcp.config.McpServerRegistryProperties;
import com.kellen.mcp.entity.dto.McpToolCallRequest;
import com.kellen.mcp.entity.dto.McpToolCallResponse;
import com.kellen.mcp.entity.dto.McpToolDescriptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Streamable HTTP MCP JSON-RPC 协议适配服务。
 *
 * <p>协议层只负责 JSON-RPC 方法和 MCP 响应结构转换；真实工具路由、
 * 租户隔离、连接器凭据、限流和审计仍由 {@link McpToolCatalogService}
 * 及后续 connector 层完成。</p>
 */
@Service
public class McpProtocolService {

    private static final String JSON_RPC_VERSION = "2.0";

    private final McpToolCatalogService toolCatalogService;
    private final McpServerRegistryProperties registryProperties;

    public McpProtocolService(McpToolCatalogService toolCatalogService,
                              McpServerRegistryProperties registryProperties) {
        this.toolCatalogService = toolCatalogService;
        this.registryProperties = registryProperties;
    }

    /**
     * 处理 MCP JSON-RPC 请求。
     *
     * @param request JSON-RPC 请求体
     * @return 有响应的请求返回 JSON-RPC 响应；通知类请求返回空
     */
    public Optional<Map<String, Object>> handle(Map<String, Object> request) {
        Object id = request.get("id");
        String method = stringValue(request.get("method"));
        if (method.isBlank()) {
            return Optional.of(error(id, -32600, "JSON-RPC method is required"));
        }
        if (!request.containsKey("id")) {
            handleNotification(method);
            return Optional.empty();
        }
        authorize(method);

        return Optional.of(switch (method) {
            case "initialize" -> response(id, initializeResult(mapValue(request.get("params"))));
            case "tools/list" -> response(id, toolsListResult());
            case "tools/call" -> callTool(id, mapValue(request.get("params")));
            default -> error(id, -32601, "Unsupported MCP method: " + method);
        });
    }

    private void handleNotification(String method) {
        if (!"notifications/initialized".equals(method)) {
            // JSON-RPC notifications intentionally have no response.
        }
    }

    private void authorize(String method) {
        switch (method) {
            case "initialize", "tools/list" -> requireAuthority("mcp:tool:list");
            case "tools/call" -> requireAuthority("mcp:tool:call");
            default -> {
                // Unsupported methods still return JSON-RPC -32601 instead of leaking auth policy.
            }
        }
    }

    private void requireAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("MCP JSON-RPC request is not authenticated");
        }
        boolean allowed = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
        if (!allowed) {
            throw new AccessDeniedException("Missing authority: " + authority);
        }
    }

    private Map<String, Object> initializeResult(Map<String, Object> params) {
        Map<String, Object> toolsCapability = new LinkedHashMap<>();
        toolsCapability.put("listChanged", false);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", toolsCapability);

        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", NacosAiRegistrySupport.textOrDefault(registryProperties.serverName(), "mcp"));
        serverInfo.put("version", NacosAiRegistrySupport.textOrDefault(registryProperties.version(), "1.0.0"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", NacosAiRegistrySupport.textOrDefault(
                stringValue(params.get("protocolVersion")), "2025-06-18"));
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Map<String, Object> toolsListResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolCatalogService.listTools().stream().map(this::toolDescriptor).toList());
        return result;
    }

    private Map<String, Object> toolDescriptor(McpToolDescriptor descriptor) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("title", descriptor.title());
        annotations.put("readOnlyHint", true);
        annotations.put("destructiveHint", false);
        annotations.put("idempotentHint", true);
        annotations.put("openWorldHint", true);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", descriptor.name());
        tool.put("title", descriptor.title());
        tool.put("description", descriptor.description());
        tool.put("inputSchema", toolCatalogService.inputSchema(descriptor.name()));
        tool.put("annotations", annotations);
        return tool;
    }

    private Map<String, Object> callTool(Object id, Map<String, Object> params) {
        String toolName = stringValue(params.get("name"));
        if (toolName.isBlank()) {
            return error(id, -32602, "tools/call params.name is required");
        }
        Map<String, Object> arguments = mapValue(params.get("arguments"));
        McpToolCallResponse toolResponse = toolCatalogService.callTool(new McpToolCallRequest(
                toolName,
                arguments,
                stringValue(params.get("traceId"))
        ));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of(
                "type", "text",
                "text", toolResponse.message()
        )));
        result.put("structuredContent", toolResponse.result());
        result.put("isError", false);
        return response(id, result);
    }

    private Map<String, Object> response(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSON_RPC_VERSION);
        response.put("id", id);
        response.put("error", error);
        return response;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, mapValue) -> {
            if (key != null) {
                result.put(String.valueOf(key), mapValue);
            }
        });
        return result;
    }
}
