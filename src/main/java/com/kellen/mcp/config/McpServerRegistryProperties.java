package com.kellen.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP Server 写入 Nacos AI Registry / MCP 管理的配置。
 *
 * <p>这里只描述 MCP Server 的发布行为和服务发现引用，不保存入站
 * clientSecret、出站三方系统 token 或任何业务密钥。</p>
 *
 * @param enabled       是否启用 MCP Server 自动发布。
 * @param serverAddr    Nacos 服务端地址，建议引用 {@code custom.infra-nacos-addr}。
 * @param namespace     发布使用的 Nacos namespace，与当前服务发现 namespace 保持一致。
 * @param username      Nacos 鉴权用户名。
 * @param password      Nacos 鉴权密码。
 * @param contextPath   Nacos 上下文路径，默认 {@code /nacos}。
 * @param serverName    MCP 管理中展示和订阅使用的 Server 名称。
 * @param version       遗留手工版本键，仅保留配置兼容；实际发布版本来自制品构建信息。
 * @param description   MCP Server 描述，为空时使用服务默认描述。
 * @param protocol      MCP 协议，默认使用 Streamable HTTP。
 * @param exportPath    MCP Streamable HTTP JSON-RPC 入口路径。
 * @param endpointType  Nacos MCP endpoint 类型，默认 REF 引用 Nacos 服务发现。
 * @param serviceName   REF endpoint 引用的 Nacos 服务名。
 * @param groupName     REF endpoint 引用的 Nacos 服务分组。
 * @param transportProtocol endpoint 的 HTTP URL 协定，仅允许 http/https，与 MCP transport 类型分开。
 * @param advertiseHost DIRECT endpoint 使用的可达地址。
 * @param advertisePort DIRECT endpoint 使用的可达端口；为 0 时回退到 server.port。
 * @param publicBaseUrl AI Registry 对外网关根地址；非空时优先以 DIRECT 宣告网关域名，不改 Naming 实例。
 */
@ConfigurationProperties(prefix = "mcp.server.registry")
public record McpServerRegistryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String serverAddr,
        @DefaultValue("") String namespace,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("/nacos") String contextPath,
        @DefaultValue("mcp") String serverName,
        @DefaultValue("1.0.3") String version,
        @DefaultValue("") String description,
        @DefaultValue("mcp-streamable") String protocol,
        @DefaultValue("/api/mcp") String exportPath,
        @DefaultValue("REF") String endpointType,
        @DefaultValue("mcp") String serviceName,
        @DefaultValue("DEFAULT_GROUP") String groupName,
        @DefaultValue("http") String transportProtocol,
        @DefaultValue("") String advertiseHost,
        @DefaultValue("0") int advertisePort,
        @DefaultValue("") String publicBaseUrl
) {
}
