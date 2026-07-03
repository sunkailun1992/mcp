package com.kellen.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP OAuth Resource Server 配置。
 *
 * <p>该配置只描述入站 OAuth token 校验策略，不保存 client secret。</p>
 *
 * @param enabled             是否启用 MCP OAuth 资源服务校验
 * @param issuer              允许的 JWT issuer
 * @param audiences           允许的 audience/resource，空格或逗号分隔
 * @param authorizationServer OAuth 授权服务器外部地址，为空时使用当前请求基准地址
 * @param externalBaseUrl     MCP 对外访问基准地址，为空时使用转发头或当前请求基准地址
 * @param readScope           查询工具目录所需 scope
 * @param callScope           调用工具所需 scope
 */
@ConfigurationProperties(prefix = "mcp.oauth.resource-server")
public record McpOAuthResourceServerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:7100") String issuer,
        @DefaultValue("http://localhost:7100/api/mcp") String audiences,
        @DefaultValue("http://localhost:7100") String authorizationServer,
        @DefaultValue("http://localhost:7100") String externalBaseUrl,
        @DefaultValue("mcp.tools.read") String readScope,
        @DefaultValue("mcp.tools.call") String callScope
) {
}
