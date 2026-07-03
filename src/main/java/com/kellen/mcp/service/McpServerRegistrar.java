package com.kellen.mcp.service;

import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointSpec;
import com.alibaba.nacos.api.ai.model.mcp.McpServerBasicInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerRemoteServiceConfig;
import com.alibaba.nacos.api.ai.model.mcp.McpServiceRef;
import com.alibaba.nacos.api.ai.model.mcp.McpTool;
import com.alibaba.nacos.api.ai.model.mcp.McpToolAnnotations;
import com.alibaba.nacos.api.ai.model.mcp.McpToolMeta;
import com.alibaba.nacos.api.ai.model.mcp.McpToolSpecification;
import com.alibaba.nacos.api.ai.model.mcp.registry.ServerVersionDetail;
import com.alibaba.nacos.api.exception.NacosException;
import com.kellen.mcp.config.McpServerRegistryProperties;
import com.kellen.mcp.entity.dto.McpToolDescriptor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nacos AI Registry MCP Server 自动发布器。
 *
 * <p>该注册器只发布 MCP Server 元数据、工具说明和服务发现引用。
 * 真正的入站认证仍由 {@code user} 和网关链路负责，出站三方系统凭据
 * 也不会写入 Nacos MCP 管理元数据。</p>
 */
@Service
public class McpServerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(McpServerRegistrar.class);

    private static final int RELEASE_ATTEMPTS = 2;
    private static final long RELEASE_RETRY_DELAY_MILLIS = 1000L;

    private final McpServerRegistryProperties properties;
    private final McpToolCatalogService toolCatalogService;
    private final int serverPort;

    private volatile AiService aiService;

    public McpServerRegistrar(McpServerRegistryProperties properties,
                              McpToolCatalogService toolCatalogService,
                              @Value("${server.port:7700}") int serverPort) {
        this.properties = properties;
        this.toolCatalogService = toolCatalogService;
        this.serverPort = serverPort;
    }

    /**
     * Spring 容器就绪后发布 MCP Server。
     *
     * @param event 容器刷新事件
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        register();
    }

    /**
     * 发布 MCP Server 元数据到 Nacos AI Registry / MCP 管理。
     */
    public void register() {
        if (!properties.enabled() || !StringUtils.hasText(properties.serverAddr())) {
            log.info("MCP Server 自动发布未启用，跳过 Nacos AI Registry MCP 管理发布。");
            return;
        }
        for (int attempt = 1; attempt <= RELEASE_ATTEMPTS; attempt++) {
            try {
                String mcpId = currentAiService().releaseMcpServer(
                        buildServerSpecification(),
                        buildToolSpecification(),
                        buildEndpointSpecification()
                );
                log.info("已发布 MCP Server: name={}, version={}, mcpId={}, exportPath={}",
                        properties.serverName(), properties.version(), mcpId, properties.exportPath());
                return;
            } catch (NacosException | RuntimeException exception) {
                if (isAlreadyReleased(exception)) {
                    log.info("MCP Server 已存在，按幂等发布成功处理，name={}, version={}, message={}",
                            properties.serverName(), properties.version(), exception.getMessage());
                    return;
                }
                if (attempt >= RELEASE_ATTEMPTS) {
                    log.error("发布 MCP Server 失败，name={}, version={}, attempts={}, message={}",
                            properties.serverName(), properties.version(), attempt, exception.getMessage());
                    return;
                }
                log.warn("发布 MCP Server 失败，准备重试，name={}, version={}, attempt={}, message={}",
                        properties.serverName(), properties.version(), attempt, exception.getMessage());
                sleepBeforeRetry();
            }
        }
    }

    private boolean isAlreadyReleased(Exception exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("already exist");
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
     * 进程退出时释放 Nacos SDK 客户端。
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
            log.debug("关闭 MCP Server Nacos AI Registry SDK 客户端失败，message={}", exception.getMessage());
        }
    }

    private McpServerBasicInfo buildServerSpecification() {
        McpServerBasicInfo server = new McpServerBasicInfo();
        server.setName(NacosAiRegistrySupport.textOrDefault(properties.serverName(), "mcp"));
        server.setProtocol(normalizedProtocol());
        server.setFrontProtocol(frontProtocolFor(normalizedProtocol()));
        server.setDescription(NacosAiRegistrySupport.textOrDefault(
                properties.description(),
                "Kellen MCP 三方系统工具接入服务，提供受控工具目录和 Streamable HTTP JSON-RPC 调用入口。"
        ));
        server.setEnabled(true);
        server.setStatus(AiConstants.Mcp.MCP_STATUS_ACTIVE);
        server.setVersion(NacosAiRegistrySupport.textOrDefault(properties.version(), "1.0.0"));
        server.setVersionDetail(versionDetail());
        server.setRemoteServerConfig(remoteServerConfig());
        if (StringUtils.hasText(properties.namespace())) {
            server.setNamespaceId(properties.namespace().trim());
        }
        return server;
    }

    private ServerVersionDetail versionDetail() {
        ServerVersionDetail detail = new ServerVersionDetail();
        detail.setVersion(NacosAiRegistrySupport.textOrDefault(properties.version(), "1.0.0"));
        detail.setRelease_date(LocalDate.now().toString());
        detail.setIs_latest(true);
        return detail;
    }

    private McpServerRemoteServiceConfig remoteServerConfig() {
        McpServerRemoteServiceConfig remote = new McpServerRemoteServiceConfig();
        remote.setExportPath(NacosAiRegistrySupport.textOrDefault(properties.exportPath(), "/api/mcp"));

        McpServiceRef serviceRef = new McpServiceRef();
        serviceRef.setNamespaceId(NacosAiRegistrySupport.text(properties.namespace()));
        serviceRef.setGroupName(NacosAiRegistrySupport.textOrDefault(properties.groupName(), "DEFAULT_GROUP"));
        serviceRef.setServiceName(NacosAiRegistrySupport.textOrDefault(properties.serviceName(), "mcp"));
        serviceRef.setTransportProtocol(frontProtocolFor(normalizedProtocol()));
        remote.setServiceRef(serviceRef);
        return remote;
    }

    private McpToolSpecification buildToolSpecification() {
        McpToolSpecification specification = new McpToolSpecification();
        List<McpToolDescriptor> descriptors = toolCatalogService.listTools();
        specification.setTools(descriptors.stream().map(this::mcpTool).toList());

        Map<String, McpToolMeta> toolsMeta = new LinkedHashMap<>();
        for (McpToolDescriptor descriptor : descriptors) {
            McpToolMeta meta = new McpToolMeta();
            meta.setEnabled(descriptor.enabled());
            meta.setInvokeContext(Map.of(
                    "toolName", descriptor.name(),
                    "system", descriptor.system(),
                    "action", descriptor.action()
            ));
            toolsMeta.put(descriptor.name(), meta);
        }
        specification.setToolsMeta(toolsMeta);
        specification.setExtensions(Map.of(
                "authProvider", "user",
                "protocolEndpoint", NacosAiRegistrySupport.textOrDefault(properties.exportPath(), "/api/mcp")
        ));
        return specification;
    }

    private McpTool mcpTool(McpToolDescriptor descriptor) {
        McpTool tool = new McpTool();
        tool.setName(descriptor.name());
        tool.setDescription(descriptor.description());
        tool.setInputSchema(toolCatalogService.inputSchema(descriptor.name()));

        McpToolAnnotations annotations = new McpToolAnnotations();
        annotations.setTitle(descriptor.title());
        annotations.setReadOnlyHint(true);
        annotations.setDestructiveHint(false);
        annotations.setIdempotentHint(true);
        annotations.setOpenWorldHint(true);
        tool.setAnnotations(annotations);
        tool.setMeta(Map.of(
                "system", descriptor.system(),
                "action", descriptor.action()
        ));
        return tool;
    }

    private McpEndpointSpec buildEndpointSpecification() {
        McpEndpointSpec endpoint = new McpEndpointSpec();
        String endpointType = NacosAiRegistrySupport.textOrDefault(
                properties.endpointType(), AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF).toUpperCase();
        if (AiConstants.Mcp.MCP_ENDPOINT_TYPE_DIRECT.equals(endpointType)) {
            endpoint.setType(AiConstants.Mcp.MCP_ENDPOINT_TYPE_DIRECT);
            endpoint.setData(directEndpointData());
            return endpoint;
        }
        endpoint.setType(AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF);
        endpoint.setData(refEndpointData());
        return endpoint;
    }

    private Map<String, String> refEndpointData() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("namespaceId", NacosAiRegistrySupport.text(properties.namespace()));
        data.put("groupName", NacosAiRegistrySupport.textOrDefault(properties.groupName(), "DEFAULT_GROUP"));
        data.put("serviceName", NacosAiRegistrySupport.textOrDefault(properties.serviceName(), "mcp"));
        return data;
    }

    private Map<String, String> directEndpointData() {
        String host = NacosAiRegistrySupport.resolveAdvertiseHost(
                properties.advertiseHost(), log, "mcp.server.registry.advertise-host");
        int port = properties.advertisePort() > 0 ? properties.advertisePort() : serverPort;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("address", host);
        data.put("port", String.valueOf(port));
        return data;
    }

    private String normalizedProtocol() {
        String protocol = NacosAiRegistrySupport.textOrDefault(
                properties.protocol(), AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE);
        if (AiConstants.Mcp.OFFICIAL_TRANSPORT_STREAMABLE.equals(protocol)) {
            return AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE;
        }
        if (AiConstants.Mcp.OFFICIAL_TRANSPORT_SSE.equals(protocol)) {
            return AiConstants.Mcp.MCP_PROTOCOL_SSE;
        }
        return protocol;
    }

    private String frontProtocolFor(String protocol) {
        if (AiConstants.Mcp.MCP_PROTOCOL_SSE.equals(protocol)) {
            return AiConstants.Mcp.OFFICIAL_TRANSPORT_SSE;
        }
        if (AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE.equals(protocol)) {
            return AiConstants.Mcp.OFFICIAL_TRANSPORT_STREAMABLE;
        }
        return protocol;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RELEASE_RETRY_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
