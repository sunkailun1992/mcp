package com.kellen.mcp.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * MCP 工具描述。
 * <p>
 * 工具目录只暴露模型和业务编排需要知道的稳定元数据，不返回三方系统密钥、
 * OAuth token、内部 endpoint 或其它敏感连接细节。
 *
 * @param name             工具唯一名称。
 * @param title            工具展示名称。
 * @param description      工具能力说明。
 * @param system           工具背后的三方系统或内部系统标识。
 * @param action           工具动作标识。
 * @param enabled          当前工具是否启用。
 * @param inputSchemaHints 入参结构提示，后续可替换为完整 JSON Schema。
 */
@Schema(description = "MCP 工具描述")
public record McpToolDescriptor(
        @Schema(description = "工具唯一名称", example = "third-party.health-check")
        String name,
        @Schema(description = "工具展示名称", example = "三方系统连通性检查")
        String title,
        @Schema(description = "工具能力说明")
        String description,
        @Schema(description = "三方系统或内部系统标识", example = "third-party")
        String system,
        @Schema(description = "工具动作标识", example = "health-check")
        String action,
        @Schema(description = "当前工具是否启用")
        boolean enabled,
        @Schema(description = "入参结构提示")
        List<String> inputSchemaHints
) {
}
