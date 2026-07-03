package com.kellen.mcp.controller;

import com.kellen.mcp.service.McpProtocolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP Streamable HTTP JSON-RPC 入口。
 */
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP Streamable HTTP", description = "MCP JSON-RPC 协议入口")
public class McpProtocolController {

    private final McpProtocolService mcpProtocolService;

    public McpProtocolController(McpProtocolService mcpProtocolService) {
        this.mcpProtocolService = mcpProtocolService;
    }

    /**
     * 处理 MCP JSON-RPC 请求。
     *
     * @param request JSON-RPC 请求体
     * @return JSON-RPC 响应；通知类请求返回 202
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('mcp:tool:list','mcp:tool:call')")
    @Operation(summary = "MCP JSON-RPC 入口", description = "支持 initialize、tools/list 和 tools/call")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody Map<String, Object> request) {
        return mcpProtocolService.handle(request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.accepted().build());
    }
}
