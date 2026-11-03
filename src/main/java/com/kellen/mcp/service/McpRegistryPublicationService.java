package com.kellen.mcp.service;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointSpec;
import com.alibaba.nacos.api.ai.model.mcp.McpServerBasicInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServiceRef;
import com.alibaba.nacos.api.ai.model.mcp.McpToolSpecification;
import com.alibaba.nacos.api.ai.model.mcp.registry.ServerVersionDetail;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP 发现元数据的不可变发布：制品版本决定顺序，内容摘要决定同制品的元数据修订。
 * 锁只保护遵循本流程的实例；不把 Nacos/Redis 当成跨系统原子事务。
 */
@Service
public class McpRegistryPublicationService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectProvider<RedissonClient> redisson;
    private final McpReleaseVersion releaseVersion;

    /** 不在构造时取得 Redis 客户端，关闭 Registry 时保持零注册依赖。 */
    public McpRegistryPublicationService(ObjectProvider<RedissonClient> redisson,
                                        McpReleaseVersion releaseVersion) {
        this.redisson = redisson;
        this.releaseVersion = releaseVersion;
    }

    /** 发布结果明确区分内容核对成功与保留更新的 latest。 */
    public enum Outcome { PUBLISHED, VERIFIED, VERIFIED_NOT_LATEST, NEWER_VERSION_PRESENT }

    /** 在 namespace/资源维度锁内发布并读回 MCP Server。 */
    public Outcome publishMcp(AiService ai, String serverAddr, String namespace,
                              McpServerBasicInfo server, McpToolSpecification tools,
                              McpEndpointSpec endpoint) throws NacosException, InterruptedException {
        JsonNode expected = mcpContract(server, tools, endpoint);
        String version = revision(expected);
        server.setVersion(version);
        server.getVersionDetail().setVersion(version);
        RLock lock = acquire(serverAddr, namespace, "mcp", server.getName());
        try {
            McpServerDetailInfo latest = latestMcp(ai, server.getName());
            if (latest != null && newerThanArtifact(mcpVersion(latest))) {
                return Outcome.NEWER_VERSION_PRESENT;
            }
            // 3.2.2 查询不存在的已有服务版本会返回通用 ErrorResponse，令 RPC 连接转为 UNHEALTHY。
            // 用版本清单判断是否需要精确核对，缺版直接发布，保留写后精确读回。
            if (latest != null && containsMcpVersion(latest, version)) {
                McpServerDetailInfo existing = version.equals(mcpVersion(latest))
                        ? latest : findMcp(ai, server.getName(), version);
                verifyMcp(existing, version, expected, endpoint.getType());
                return version.equals(mcpVersion(latest))
                        ? Outcome.VERIFIED : Outcome.VERIFIED_NOT_LATEST;
            }
            assertOwned(lock);
            try {
                ai.releaseMcpServer(server, tools, endpoint);
            } catch (NacosException exception) {
                // SDK 的同版竞争只在精确内容读回一致时算成功，不按错误文本放行。
                if (exception.getErrCode() != 409) {
                    throw exception;
                }
            }
            assertOwned(lock);
            verifyMcp(findMcp(ai, server.getName(), version), version, expected, endpoint.getType());
            McpServerDetailInfo published = latestMcp(ai, server.getName());
            if (published == null || !version.equals(mcpVersion(published))) {
                if (published != null && newerThanArtifact(mcpVersion(published))) {
                    return Outcome.NEWER_VERSION_PRESENT;
                }
                throw new IllegalStateException("MCP_REGISTRY_LATEST_READBACK_FAILED");
            }
            return Outcome.PUBLISHED;
        } finally {
            unlockOwned(lock);
        }
    }

    /** AgentCard 使用同一制品版和内容修订规则，不依赖遗留 card-version。 */
    public Outcome publishAgent(AiService ai, String serverAddr, String namespace,
                                AgentCard card) throws NacosException, InterruptedException {
        JsonNode expected = agentContract(card);
        String version = revision(expected);
        card.setVersion(version);
        RLock lock = acquire(serverAddr, namespace, "agent", card.getName());
        try {
            AgentCardDetailInfo latest = findAgent(ai, card.getName(), null);
            if (latest != null && newerThanArtifact(latest.getVersion())) {
                return Outcome.NEWER_VERSION_PRESENT;
            }
            AgentCardDetailInfo existing = findAgent(ai, card.getName(), version);
            if (existing != null) {
                verifyAgent(existing, version, expected);
                return latest != null && version.equals(latest.getVersion())
                        ? Outcome.VERIFIED : Outcome.VERIFIED_NOT_LATEST;
            }
            assertOwned(lock);
            try {
                ai.releaseAgentCard(card, AiConstants.A2a.A2A_ENDPOINT_TYPE_URL, true);
            } catch (NacosException exception) {
                if (exception.getErrCode() != 409) {
                    throw exception;
                }
            }
            assertOwned(lock);
            verifyAgent(findAgent(ai, card.getName(), version), version, expected);
            AgentCardDetailInfo published = findAgent(ai, card.getName(), null);
            if (published == null || !version.equals(published.getVersion())) {
                if (published != null && newerThanArtifact(published.getVersion())) {
                    return Outcome.NEWER_VERSION_PRESENT;
                }
                throw new IllegalStateException("MCP_REGISTRY_LATEST_READBACK_FAILED");
            }
            return Outcome.PUBLISHED;
        } finally {
            unlockOwned(lock);
        }
    }

    private String revision(JsonNode contract) {
        return releaseVersion.requireVersion() + "-c" + digest(contract.toString()).substring(0, 24);
    }

    private boolean newerThanArtifact(String registeredVersion) {
        String base = registeredVersion == null ? null : registeredVersion.replaceFirst("-c[0-9a-f]{24}$", "");
        return McpReleaseVersion.compare(base, releaseVersion.requireVersion()) > 0;
    }

    private RLock acquire(String serverAddr, String namespace, String type, String name) throws InterruptedException {
        RedissonClient client = redisson.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("MCP_REGISTRY_LOCK_UNAVAILABLE");
        }
        String scope = NacosAiRegistrySupport.normalizeServerAddr(serverAddr) + "\n"
                + namespace(namespace) + "\n" + type + "\n" + name;
        RLock lock = client.getLock("mcp:registry:publish:" + digest(scope));
        // 省略 leaseTime 使用 Redisson watchdog，避免慢查询越过固定短租约后并发写入。
        if (!lock.tryLock(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("MCP_REGISTRY_LOCK_BUSY");
        }
        return lock;
    }

    private static void assertOwned(RLock lock) {
        if (!lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("MCP_REGISTRY_LOCK_LOST");
        }
    }

    private static void unlockOwned(RLock lock) {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    private McpServerDetailInfo latestMcp(AiService ai, String name) throws NacosException {
        McpServerDetailInfo detail = findMcp(ai, name, null);
        if (detail == null) {
            return null;
        }
        // 3.2.2 的无版本查询可能返回最后新增版本，不能假设它就是发布的 latest。
        List<ServerVersionDetail> latest = detail.getAllVersions() == null ? List.of()
                : detail.getAllVersions().stream().filter(v -> Boolean.TRUE.equals(v.getIs_latest())).toList();
        if (latest.size() != 1) {
            throw new IllegalStateException("MCP_REGISTRY_AMBIGUOUS_LATEST");
        }
        String version = latest.get(0).getVersion();
        McpServerDetailInfo result = version.equals(mcpVersion(detail)) ? detail : findMcp(ai, name, version);
        if (result == null || !version.equals(mcpVersion(result))) {
            throw new IllegalStateException("MCP_REGISTRY_LATEST_READBACK_FAILED");
        }
        return result;
    }

    private static McpServerDetailInfo findMcp(AiService ai, String name, String version) throws NacosException {
        try {
            return ai.getMcpServer(name, version);
        } catch (NacosException exception) {
            // SDK 只保留 HTTP 级错误码；403、超时和连接错误均不能当成不存在。
            if (exception.getErrCode() == 404) {
                return null;
            }
            throw exception;
        }
    }

    /** 只依据 Registry 返回的完整版本清单判断存在性；不以探测不存在版本代替清单。 */
    private static boolean containsMcpVersion(McpServerDetailInfo server, String version) {
        if (server.getAllVersions() == null) {
            throw new IllegalStateException("MCP_REGISTRY_INCOMPLETE_VERSIONS");
        }
        return server.getAllVersions().stream().anyMatch(detail -> version.equals(detail.getVersion()));
    }

    private static AgentCardDetailInfo findAgent(AiService ai, String name, String version) throws NacosException {
        try {
            return ai.getAgentCard(name, version, AiConstants.A2a.A2A_ENDPOINT_TYPE_URL);
        } catch (NacosException exception) {
            if (exception.getErrCode() == 404) {
                return null;
            }
            throw exception;
        }
    }

    private static String mcpVersion(McpServerBasicInfo server) {
        return server.getVersionDetail() == null ? server.getVersion() : server.getVersionDetail().getVersion();
    }

    private static void verifyMcp(McpServerDetailInfo actual, String version, JsonNode expected, String endpointType) {
        if (actual == null || !version.equals(mcpVersion(actual))
                || !expected.equals(mcpContract(actual, actual.getToolSpec(), endpoint(actual, endpointType)))) {
            throw new IllegalStateException("MCP_REGISTRY_CONTENT_MISMATCH");
        }
    }

    private static void verifyAgent(AgentCardDetailInfo actual, String version, JsonNode expected) {
        if (actual == null || !version.equals(actual.getVersion()) || !expected.equals(agentContract(actual))) {
            throw new IllegalStateException("MCP_REGISTRY_CONTENT_MISMATCH");
        }
    }

    /** 只核对本服务发布的协议、工具和 endpoint，不包含 Nacos 生成 ID/时钟。 */
    private static JsonNode mcpContract(McpServerBasicInfo server, McpToolSpecification tools, McpEndpointSpec endpoint) {
        if (tools == null || server.getRemoteServerConfig() == null) {
            throw new IllegalStateException("MCP_REGISTRY_INCOMPLETE_CONTENT");
        }
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("name", server.getName());
        contract.put("protocol", server.getProtocol());
        contract.put("frontProtocol", server.getFrontProtocol());
        contract.put("description", server.getDescription());
        contract.put("enabled", server.isEnabled());
        contract.put("status", server.getStatus());
        contract.put("exportPath", server.getRemoteServerConfig().getExportPath());
        McpServiceRef ref = server.getRemoteServerConfig().getServiceRef();
        // Nacos 从 endpoint 重建 serviceRef；缺失或变化的 URL scheme 必须使读回失败。
        contract.put("transportProtocol", ref == null ? "" : NacosAiRegistrySupport.text(ref.getTransportProtocol()));
        boolean serviceReference = AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF.equals(endpoint.getType());
        contract.put("serviceRef", !serviceReference || ref == null ? null
                : Map.of("namespaceId", namespace(ref.getNamespaceId()),
                "groupName", ref.getGroupName(), "serviceName", ref.getServiceName()));
        Map<String, String> endpointData = new LinkedHashMap<>(endpoint.getData());
        if (serviceReference) {
            endpointData.put("namespaceId", namespace(endpointData.get("namespaceId")));
        }
        contract.put("endpoint", Map.of("type", endpoint.getType(), "data", endpointData));
        contract.put("tools", tools.getTools().stream().sorted(Comparator.comparing(t -> t.getName())).toList());
        contract.put("toolsMeta", tools.getToolsMeta());
        contract.put("extensions", tools.getExtensions());
        contract.put("securitySchemes", tools.getSecuritySchemes());
        return canonical(JSON.valueToTree(contract));
    }

    private static McpEndpointSpec endpoint(McpServerDetailInfo actual, String type) {
        McpEndpointSpec endpoint = new McpEndpointSpec();
        endpoint.setType(type);
        if (AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF.equals(type)) {
            McpServiceRef ref = actual.getRemoteServerConfig().getServiceRef();
            if (ref == null) {
                throw new IllegalStateException("MCP_REGISTRY_INCOMPLETE_ENDPOINT");
            }
            endpoint.setData(Map.of("namespaceId", ref.getNamespaceId(), "groupName", ref.getGroupName(),
                    "serviceName", ref.getServiceName(),
                    "transportProtocol", NacosAiRegistrySupport.text(ref.getTransportProtocol())));
        } else {
            List<McpEndpointInfo> endpoints = actual.getBackendEndpoints();
            if (endpoints == null || endpoints.size() != 1) {
                throw new IllegalStateException("MCP_REGISTRY_INCOMPLETE_ENDPOINT");
            }
            McpEndpointInfo info = endpoints.get(0);
            endpoint.setData(Map.of("address", info.getAddress(), "port", Integer.toString(info.getPort()),
                    "transportProtocol", NacosAiRegistrySupport.text(info.getProtocol())));
        }
        return endpoint;
    }

    private static JsonNode agentContract(AgentCard card) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("name", card.getName());
        contract.put("description", card.getDescription());
        contract.put("protocolVersion", card.getProtocolVersion());
        contract.put("capabilities", card.getCapabilities());
        contract.put("skills", card.getSkills());
        contract.put("supportedInterfaces", card.getSupportedInterfaces());
        contract.put("url", card.getUrl());
        contract.put("preferredTransport", card.getPreferredTransport());
        contract.put("provider", card.getProvider());
        return canonical(JSON.valueToTree(contract));
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            value.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode array = JSON.createArrayNode();
            value.forEach(item -> array.add(canonical(item)));
            return array;
        }
        return value;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MCP_REGISTRY_DIGEST_UNAVAILABLE");
        }
    }

    private static String namespace(String value) {
        return NacosAiRegistrySupport.textOrDefault(value, "public");
    }
}
