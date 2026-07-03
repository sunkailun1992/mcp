package com.kellen.mcp.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * MCP 工具调用请求。
 * <p>
 * 调用方只能提交工具名和业务参数。三方系统凭据、OAuth token、API key、
 * 签名密钥和内部 endpoint 必须由 MCP 服务端按租户和工具配置解析。
 *
 * @param toolName  工具唯一名称。
 * @param arguments 工具业务参数。
 * @param traceId   调用方追踪标识，可为空；服务端仍需生成自己的 traceId。
 */
@Schema(description = "MCP 工具调用请求")
public record McpToolCallRequest(
        @NotBlank(message = "toolName不能为空")
        @Schema(description = "工具唯一名称", example = "third-party.health-check")
        String toolName,
        @Schema(description = "工具业务参数")
        Map<String, Object> arguments,
        @Schema(description = "调用方追踪标识")
        String traceId
) {
}
