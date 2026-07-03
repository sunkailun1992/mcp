package com.kellen.mcp.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * MCP 工具调用响应。
 * <p>
 * 响应体只返回业务可见结果和稳定状态，不泄露三方系统原始 token、
 * 原始认证头、完整内部 URL 或异常堆栈。
 *
 * @param toolName 工具唯一名称。
 * @param state    调用状态。
 * @param result   工具业务结果。
 * @param message  稳定提示信息。
 */
@Schema(description = "MCP 工具调用响应")
public record McpToolCallResponse(
        @Schema(description = "工具唯一名称")
        String toolName,
        @Schema(description = "调用状态", example = "ACCEPTED")
        String state,
        @Schema(description = "工具业务结果")
        Map<String, Object> result,
        @Schema(description = "稳定提示信息")
        String message
) {
}
