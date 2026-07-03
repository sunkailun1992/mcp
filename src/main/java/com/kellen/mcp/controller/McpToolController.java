package com.kellen.mcp.controller;

import com.kellen.mcp.entity.dto.McpToolCallRequest;
import com.kellen.mcp.entity.dto.McpToolCallResponse;
import com.kellen.mcp.entity.dto.McpToolDescriptor;
import com.kellen.mcp.service.McpToolCatalogService;
import com.kellen.utils.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 工具目录与工具调用入口。
 * <p>
 * Controller 只处理 HTTP 协议、权限和统一响应。三方系统凭据解析、工具路由、
 * 调用审计、限流和失败重试必须放在 Service/connector 层。
 */
@RestController
@RequestMapping("/api/mcp")
@Tag(name = "MCP 工具接入", description = "维护 MCP 工具目录和三方系统工具调用入口")
public class McpToolController {

    private final McpToolCatalogService mcpToolCatalogService;

    public McpToolController(McpToolCatalogService mcpToolCatalogService) {
        this.mcpToolCatalogService = mcpToolCatalogService;
    }

    /**
     * 查询 MCP 工具目录。
     *
     * @return 工具目录
     */
    @GetMapping("/tools")
    @PreAuthorize("hasAuthority('mcp:tool:list')")
    @Operation(summary = "查询 MCP 工具目录", description = "返回当前租户和环境可暴露给 AI 编排层的 MCP 工具元数据")
    public ApiResponse<List<McpToolDescriptor>> listTools() {
        return ApiResponse.success(mcpToolCatalogService.listTools());
    }

    /**
     * 调用 MCP 工具。
     *
     * @param request 工具调用请求
     * @return 工具调用结果
     */
    @PostMapping("/tools/call")
    @PreAuthorize("hasAuthority('mcp:tool:call')")
    @Operation(summary = "调用 MCP 工具", description = "按工具名和业务参数调用三方系统连接器，返回稳定结构化结果")
    public ApiResponse<McpToolCallResponse> callTool(@Valid @RequestBody McpToolCallRequest request) {
        return ApiResponse.success(mcpToolCatalogService.callTool(request));
    }
}
