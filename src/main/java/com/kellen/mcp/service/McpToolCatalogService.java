package com.kellen.mcp.service;

import com.kellen.mcp.entity.dto.McpToolCallRequest;
import com.kellen.mcp.entity.dto.McpToolCallResponse;
import com.kellen.mcp.entity.dto.McpToolDescriptor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具目录和工具调用编排服务。
 * <p>
 * 当前先保留最小可编译骨架：工具目录返回一个连通性检查占位工具，
 * 工具调用只做受控占位响应。后续接入真实三方系统时，应在本服务下
 * 拆出 connector、credential、audit、rate-limit 和 adapter 边界，
 * 不把三方系统调用逻辑写进 Controller。
 */
@Service
public class McpToolCatalogService {

    private static final String HEALTH_CHECK_TOOL = "third-party.health-check";
    private static final String USER_TAGS_BY_WECHAT_NAME_TOOL = "user-profile.tags-by-wechat-name";
    private static final String TEST_WECHAT_USER_NAME = "Autumn";

    /**
     * 返回当前 MCP 服务可暴露给 AI 编排层的工具目录。
     *
     * @return 工具描述列表
     */
    public List<McpToolDescriptor> listTools() {
        return List.of(
                new McpToolDescriptor(
                        HEALTH_CHECK_TOOL,
                        "三方系统连通性检查",
                        "用于验证 MCP 服务到三方系统连接器的基础链路，正式 connector 接入后替换为真实实现。",
                        "third-party",
                        "health-check",
                        true,
                        List.of("systemCode: string", "tenantId: string")
                ),
                new McpToolDescriptor(
                        USER_TAGS_BY_WECHAT_NAME_TOOL,
                        "通过微信用户名称获取用户标签",
                        "根据微信用户名称返回测试用户标签，供语聚智能体配置调用并验证后续用户画像 connector 接入链路。",
                        "user-profile",
                        "tags-by-wechat-name",
                        true,
                        List.of("wechatUserName: string", "tenantId: string")
                )
        );
    }

    /**
     * 返回工具入参 JSON Schema。
     *
     * <p>工具协议结构属于代码契约，不能只靠 Nacos 配置中心临时拼装。
     * 后续真实 connector 接入时，应在工具定义代码或数据库元数据中维护
     * 稳定 schema，再由 Nacos MCP 注册器发布出去。</p>
     *
     * @param toolName 工具唯一名称
     * @return JSON Schema
     */
    public Map<String, Object> inputSchema(String toolName) {
        if (HEALTH_CHECK_TOOL.equals(toolName)) {
            return healthCheckInputSchema();
        }
        if (USER_TAGS_BY_WECHAT_NAME_TOOL.equals(toolName)) {
            return userTagsByWechatNameInputSchema();
        }
        return Map.of("type", "object", "properties", Map.of());
    }

    private Map<String, Object> healthCheckInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("systemCode", Map.of(
                "type", "string",
                "description", "三方系统编码"
        ));
        properties.put("tenantId", Map.of(
                "type", "string",
                "description", "租户标识"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("systemCode"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> userTagsByWechatNameInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("wechatUserName", Map.of(
                "type", "string",
                "description", "微信用户名称，例如 Autumn"
        ));
        properties.put("tenantId", Map.of(
                "type", "string",
                "description", "租户标识，可为空"
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("wechatUserName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 调用 MCP 工具。
     *
     * <p>正式三方系统接入前只返回受控占位结果，避免空项目误连生产系统。
     * 后续实现必须先校验工具启用状态、租户权限、连接器凭据、限流和审计。</p>
     *
     * @param request 工具调用请求
     * @return 工具调用响应
     */
    public McpToolCallResponse callTool(McpToolCallRequest request) {
        if (USER_TAGS_BY_WECHAT_NAME_TOOL.equals(request.toolName())) {
            return userTagsByWechatName(request);
        }
        Map<String, Object> result = Map.of(
                "toolRegistered", HEALTH_CHECK_TOOL.equals(request.toolName()),
                "connectorReady", false
        );
        return new McpToolCallResponse(
                request.toolName(),
                "ACCEPTED",
                result,
                "MCP 工具入口已就绪，真实三方系统 connector 尚未启用。"
        );
    }

    private McpToolCallResponse userTagsByWechatName(McpToolCallRequest request) {
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        String wechatUserName = stringArgument(arguments, "wechatUserName");
        String tenantId = stringArgument(arguments, "tenantId");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolRegistered", true);
        result.put("lookupReady", false);
        result.put("source", "test-fixture");
        result.put("wechatUserName", wechatUserName);
        result.put("tenantId", tenantId);

        if (wechatUserName.isBlank()) {
            result.put("matched", false);
            result.put("confidence", 0);
            result.put("tags", List.of());
            return new McpToolCallResponse(
                    request.toolName(),
                    "REJECTED",
                    result,
                    "wechatUserName 不能为空，未执行用户标签测试查询。"
            );
        }

        boolean matched = TEST_WECHAT_USER_NAME.equalsIgnoreCase(wechatUserName);
        List<String> tags = matched ? userTagsForWechatName() : List.of();
        result.put("matched", matched);
        result.put("confidence", matched ? 100 : 0);
        result.put("tags", tags);
        return new McpToolCallResponse(
                request.toolName(),
                "ACCEPTED",
                result,
                matched
                        ? "微信用户名称标签测试查询完成，当前返回 Autumn 的测试标签，真实用户画像 connector 尚未启用。"
                        : "未命中测试用户标签，当前测试桩只支持微信用户名称 Autumn。"
        );
    }

    private List<String> userTagsForWechatName() {
        return List.of("微信用户", "语聚测试用户", "高活跃", "健康管理关注");
    }

    private String stringArgument(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
