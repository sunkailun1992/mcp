package com.kellen.mcp.service;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.a2a.AgentProvider;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.alibaba.nacos.api.exception.NacosException;
import com.kellen.mcp.config.McpAgentRegistryProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP AgentCard 自动注册器。
 * <p>
 * MCP 服务启动后可通过 Nacos 官方 {@link AiService} SDK 发布一个
 * {@code mcp-tool-agent}，让主 AI 服务或 Agent 编排层从 Nacos AI Registry
 * 发现 MCP 工具目录和工具调用入口。注册失败采用 fail-soft：只记录日志，
 * 不阻断 MCP 服务启动。
 */
@Service
public class McpAgentCardRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpAgentCardRegistrar.class);

    private static final String TRANSPORT_HTTP_JSON = "HTTP+JSON";
    private static final int RELEASE_ATTEMPTS = 2;
    private static final long RELEASE_RETRY_DELAY_MILLIS = 1000L;

    private final McpAgentRegistryProperties properties;
    private final int serverPort;

    private volatile AiService aiService;

    public McpAgentCardRegistrar(McpAgentRegistryProperties properties,
                                 @Value("${server.port:7700}") int serverPort) {
        this.properties = properties;
        this.serverPort = serverPort;
    }

    /**
     * 容器就绪后触发自动注册。
     *
     * @param event 上下文刷新事件，仅用于获取 Spring 容器刷新时机。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        register();
    }

    /**
     * 发布 MCP AgentCard。
     */
    public void register() {
        if (!properties.enabled() || !StringUtils.hasText(properties.serverAddr())) {
            log.info("MCP AgentCard 自动注册未启用，跳过 Nacos AI Registry 发布。");
            return;
        }
        String host = NacosAiRegistrySupport.resolveAdvertiseHost(
                properties.advertiseHost(), log, "mcp.agent.registry.advertise-host");
        int port = properties.advertisePort() > 0 ? properties.advertisePort() : serverPort;
        for (int attempt = 1; attempt <= RELEASE_ATTEMPTS; attempt++) {
            try {
                currentAiService().releaseAgentCard(
                        buildCard(host, port),
                        AiConstants.A2a.A2A_ENDPOINT_TYPE_URL,
                        true
                );
                log.info("已发布 MCP AgentCard: agent={}, toolsUrl={}", properties.agentName(),
                        interfaceUrl(host, port, "/api/mcp/tools"));
                return;
            } catch (NacosException | RuntimeException exception) {
                if (attempt >= RELEASE_ATTEMPTS) {
                    log.error("发布 MCP AgentCard 失败，agent={}, attempts={}, message={}",
                            properties.agentName(), attempt, exception.getMessage());
                    return;
                }
                log.warn("发布 MCP AgentCard 失败，准备重试，agent={}, attempt={}, message={}",
                        properties.agentName(), attempt, exception.getMessage());
                sleepBeforeRetry();
            }
        }
    }

    private AiService currentAiService() throws NacosException {
        AiService service = aiService;
        if (service != null) {
            return service;
        }
        synchronized (this) {
            if (aiService == null) {
                aiService = AiFactory.createAiService(NacosAiRegistrySupport.nacosProperties(
                        properties.serverAddr(),
                        properties.namespace(),
                        properties.username(),
                        properties.password(),
                        properties.contextPath()
                ));
            }
            return aiService;
        }
    }

    /**
     * 进程关闭时释放 Nacos SDK 客户端。
     */
    @PreDestroy
    public void shutdown() {
        AiService service = aiService;
        if (service == null) {
            return;
        }
        try {
            service.shutdown();
        } catch (NacosException exception) {
            log.debug("关闭 MCP Nacos AI Registry SDK 客户端失败，message={}", exception.getMessage());
        }
    }

    private AgentCard buildCard(String host, int port) {
        AgentCard card = new AgentCard();
        card.setName(properties.agentName());
        card.setDescription("Kellen MCP 工具接入 Agent，负责暴露三方系统工具目录和受控工具调用入口。");
        card.setVersion(properties.cardVersion());
        card.setProtocolVersion(properties.protocolVersion());
        card.setCapabilities(capabilities());
        card.setSkills(skills());

        List<AgentInterface> interfaces = new ArrayList<>();
        interfaces.add(agentInterface(host, port, "/api/mcp/tools"));
        interfaces.add(agentInterface(host, port, "/api/mcp/tools/call"));
        card.setSupportedInterfaces(interfaces);
        card.setUrl(interfaces.get(0).getUrl());
        card.setPreferredTransport(TRANSPORT_HTTP_JSON);

        AgentProvider provider = new AgentProvider();
        provider.setOrganization(properties.providerOrg());
        card.setProvider(provider);
        return card;
    }

    private AgentCapabilities capabilities() {
        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setStreaming(false);
        capabilities.setPushNotifications(false);
        capabilities.setStateTransitionHistory(false);
        return capabilities;
    }

    private List<AgentSkill> skills() {
        AgentSkill toolCatalog = skill(
                "mcp-tool-catalog",
                "MCP 工具目录",
                "查询当前租户和环境允许 AI 编排层使用的 MCP 工具元数据。",
                List.of("mcp", "tools", "catalog"),
                List.of("列出可以调用的三方系统工具", "查询当前环境启用的 MCP 工具"));
        AgentSkill toolCall = skill(
                "mcp-tool-call",
                "MCP 工具调用",
                "按工具名和业务参数调用受控三方系统连接器，返回稳定结构化结果。",
                List.of("mcp", "tools", "third-party", "connector"),
                List.of("调用客户系统查询接口", "通过 MCP 工具同步三方系统数据"));
        return List.of(toolCatalog, toolCall);
    }

    private AgentSkill skill(String id, String name, String description,
                             List<String> tags, List<String> examples) {
        AgentSkill skill = new AgentSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setDescription(description);
        skill.setTags(tags);
        skill.setExamples(examples);
        return skill;
    }

    private AgentInterface agentInterface(String host, int port, String path) {
        AgentInterface agentInterface = new AgentInterface();
        agentInterface.setUrl(interfaceUrl(host, port, path));
        agentInterface.setTransport(TRANSPORT_HTTP_JSON);
        agentInterface.setProtocolBinding(TRANSPORT_HTTP_JSON);
        agentInterface.setProtocolVersion(properties.protocolVersion());
        if (StringUtils.hasText(properties.tenant())) {
            agentInterface.setTenant(properties.tenant().trim());
        }
        return agentInterface;
    }

    private String interfaceUrl(String host, int port, String path) {
        String scheme = properties.supportTls() ? "https" : "http";
        return scheme + "://" + host + ":" + port + path;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RELEASE_RETRY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
