package com.kellen.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MCP 服务写入 Nacos AI Registry 的 AgentCard 配置。
 * <p>
 * 本配置只描述 MCP 工具 AgentCard 的发布行为，不保存三方系统凭据。
 * Nacos 地址、namespace、账号、密码和第三方系统密钥必须通过环境变量或
 * Nacos 配置中心注入，不能写入仓库明文。
 *
 * @param enabled         是否启用自动注册；关闭时不连接 AI Registry，便于本地编译和单元测试。
 * @param serverAddr      Nacos 服务端地址，建议引用 {@code custom.infra-nacos-addr}。
 * @param namespace       注册使用的命名空间，与当前运行 profile 的配置中心 namespace 保持一致。
 * @param username        Nacos 鉴权用户名。
 * @param password        Nacos 鉴权密码。
 * @param contextPath     Nacos 上下文路径，默认 {@code /nacos}。
 * @param advertiseHost   AgentCard 对外暴露的服务可达地址；容器内建议配置为服务名或内网地址。
 * @param advertisePort   AgentCard 对外暴露端口；为 0 时回退到 {@code server.port}。
 * @param supportTls      AgentCard 端点是否使用 HTTPS。
 * @param protocolVersion A2A 协议版本号。
 * @param cardVersion     AgentCard 版本号，接口地址、技能或描述变化时递增。
 * @param tenant          A2A 端点租户标识，可按部署隔离需要留空或配置。
 * @param providerOrg     AgentCard provider.organization 展示名。
 * @param agentName       MCP 工具 Agent 名称，供 AI 服务从 AI Registry 读取。
 */
@ConfigurationProperties(prefix = "mcp.agent.registry")
public record McpAgentRegistryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String serverAddr,
        @DefaultValue("") String namespace,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("/nacos") String contextPath,
        @DefaultValue("") String advertiseHost,
        @DefaultValue("0") int advertisePort,
        @DefaultValue("false") boolean supportTls,
        @DefaultValue("1.0") String protocolVersion,
        @DefaultValue("1.0.0") String cardVersion,
        @DefaultValue("") String tenant,
        @DefaultValue("Kellen MCP") String providerOrg,
        @DefaultValue("mcp-tool-agent") String agentName
) {
}
