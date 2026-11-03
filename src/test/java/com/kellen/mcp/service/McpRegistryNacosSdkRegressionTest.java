package com.kellen.mcp.service;

import com.alibaba.nacos.api.ability.constant.AbilityKey;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.mcp.McpEndpointSpec;
import com.alibaba.nacos.api.ai.model.mcp.McpServerBasicInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerDetailInfo;
import com.alibaba.nacos.api.ai.model.mcp.McpServerRemoteServiceConfig;
import com.alibaba.nacos.api.ai.model.mcp.McpServiceRef;
import com.alibaba.nacos.api.ai.model.mcp.McpTool;
import com.alibaba.nacos.api.ai.model.mcp.McpToolSpecification;
import com.alibaba.nacos.api.ai.model.mcp.registry.ServerVersionDetail;
import com.alibaba.nacos.api.ai.remote.request.QueryAgentCardRequest;
import com.alibaba.nacos.api.ai.remote.request.QueryMcpServerRequest;
import com.alibaba.nacos.api.ai.remote.request.ReleaseAgentCardRequest;
import com.alibaba.nacos.api.ai.remote.request.ReleaseMcpServerRequest;
import com.alibaba.nacos.api.ai.remote.response.QueryAgentCardResponse;
import com.alibaba.nacos.api.ai.remote.response.QueryMcpServerResponse;
import com.alibaba.nacos.api.ai.remote.response.ReleaseAgentCardResponse;
import com.alibaba.nacos.api.ai.remote.response.ReleaseMcpServerResponse;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import com.alibaba.nacos.api.remote.request.Request;
import com.alibaba.nacos.api.remote.response.ErrorResponse;
import com.alibaba.nacos.api.remote.response.Response;
import com.alibaba.nacos.client.ai.NacosAiService;
import com.alibaba.nacos.client.ai.remote.AiGrpcClient;
import com.alibaba.nacos.client.security.SecurityProxy;
import com.alibaba.nacos.common.remote.ConnectionType;
import com.alibaba.nacos.common.remote.client.Connection;
import com.alibaba.nacos.common.remote.client.RpcClient;
import com.alibaba.nacos.common.remote.client.RpcClientConfig;
import com.alibaba.nacos.common.remote.client.RpcClientStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.kellen.mcp.service.McpRegistryPublicationService.Outcome.PUBLISHED;
import static com.kellen.mcp.service.McpRegistryPublicationService.Outcome.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 真 Nacos 3.2.2 SDK/RpcClient 回归，只替换最底层 Connection，不创建网络客户端或重连线程。
 * SDK 构造器被隔离，get/release、能力检查和 RpcClient.request 均执行依赖中的原方法。
 * 这证明 SDK 的状态变化，不能替代线上调用栈取证。
 *
 * <p>官方依据（固定 3.2.2）：
 * <a href="https://github.com/alibaba/nacos/blob/3.2.2/ai/src/main/java/com/alibaba/nacos/ai/remote/handler/QueryMcpServerRequestHandler.java">QueryMcpServerRequestHandler.doHandler</a>
 * 与 <a href="https://github.com/alibaba/nacos/blob/3.2.2/core/src/main/java/com/alibaba/nacos/core/remote/grpc/GrpcRequestAcceptor.java">GrpcRequestAcceptor.request</a>
 * 将缺版本异常转为 ErrorResponse；
 * <a href="https://github.com/alibaba/nacos/blob/3.2.2/common/src/main/java/com/alibaba/nacos/common/remote/client/RpcClient.java">RpcClient.request</a>
 * 会标记 UNHEALTHY，随后
 * <a href="https://github.com/alibaba/nacos/blob/3.2.2/client/src/main/java/com/alibaba/nacos/client/ai/remote/AiGrpcClient.java">AiGrpcClient.checkServerAbilityOrThrow</a>
 * 拒绝发布。AgentCard 的专用 QueryAgentCardResponse 用作对照。</p>
 */
class McpRegistryNacosSdkRegressionTest {

    private static final String NAME = "synthetic-mcp";
    private static final String NAMESPACE = "synthetic-registry-space";
    private static final String ARTIFACT = "4.2.7";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void absentExactMcpVersionPoisonsRealRpcAndNextReleaseThrowsRuntime500InsteadOfRuntime404() throws Exception {
        SdkFixture sdk = new SdkFixture();
        sdk.seed("4.2.6");
        assertThat(sdk.rpc.isRunning()).isTrue();

        assertThatThrownBy(() -> sdk.ai.getMcpServer(NAME, ARTIFACT))
                .isExactlyInstanceOf(NacosException.class)
                .satisfies(error -> assertThat(((NacosException) error).getErrCode()).isEqualTo(404));

        assertThat(sdk.status()).isEqualTo(RpcClientStatus.UNHEALTHY);
        assertThat(sdk.missingExactQueries).isEqualTo(1);
        Contract contract = contract();
        assertThatThrownBy(() -> sdk.ai.releaseMcpServer(contract.server, contract.tools, contract.endpoint))
                .isExactlyInstanceOf(NacosRuntimeException.class)
                .satisfies(error -> assertThat(((NacosRuntimeException) error).getErrCode()).isEqualTo(500));
        assertThat(sdk.releases).isZero();
        assertThat(sdk.requests).containsExactly(QueryMcpServerRequest.class);
    }

    @Test
    void absentMcpNameUsesDedicatedResponseAndDoesNotPoisonRealRpc() throws Exception {
        SdkFixture sdk = new SdkFixture();

        assertThatThrownBy(() -> sdk.ai.getMcpServer(NAME, null))
                .isExactlyInstanceOf(NacosException.class)
                .satisfies(error -> assertThat(((NacosException) error).getErrCode()).isEqualTo(404));
        assertThat(sdk.status()).isEqualTo(RpcClientStatus.RUNNING);

        Contract contract = contract();
        assertThat(sdk.ai.releaseMcpServer(contract.server, contract.tools, contract.endpoint))
                .isEqualTo("synthetic-server-id");
        assertThat(sdk.status()).isEqualTo(RpcClientStatus.RUNNING);
        assertThat(sdk.releases).isEqualTo(1);
        assertThat(sdk.missingExactQueries).isZero();
    }

    @Test
    void agentMissingVersionUsesDedicatedResponseSoRealSdkCanReleaseOnTheSameConnection() throws Exception {
        SdkFixture sdk = new SdkFixture();
        assertThatThrownBy(() -> sdk.ai.getAgentCard("synthetic-agent", ARTIFACT,
                AiConstants.A2a.A2A_ENDPOINT_TYPE_URL))
                .isExactlyInstanceOf(NacosException.class)
                .satisfies(error -> assertThat(((NacosException) error).getErrCode()).isEqualTo(404));
        assertThat(sdk.status()).isEqualTo(RpcClientStatus.RUNNING);

        AgentCard card = new AgentCard();
        card.setName("synthetic-agent");
        card.setVersion(ARTIFACT);
        card.setDescription("Synthetic SDK contract");
        card.setCapabilities(new AgentCapabilities());
        card.setProtocolVersion("1.0");
        card.setUrl("https://agent.example.invalid/api/mcp/tools");
        card.setPreferredTransport("HTTP+JSON");
        AgentInterface endpoint = new AgentInterface();
        endpoint.setUrl(card.getUrl());
        endpoint.setProtocolBinding("HTTP+JSON");
        endpoint.setTransport("HTTP+JSON");
        endpoint.setProtocolVersion("1.0");
        card.setSupportedInterfaces(List.of(endpoint));
        sdk.ai.releaseAgentCard(card, AiConstants.A2a.A2A_ENDPOINT_TYPE_URL, true);

        assertThat(sdk.agentReleases).isEqualTo(1);
        assertThat(sdk.status()).isEqualTo(RpcClientStatus.RUNNING);
        assertThat(sdk.requests).containsExactly(QueryAgentCardRequest.class, ReleaseAgentCardRequest.class);
    }

    @Test
    void publicationSkipsUnlistedExactVersionAndReadsBackNewRevisionThroughRealSdkAndRpc() throws Exception {
        SdkFixture sdk = new SdkFixture();
        sdk.seed("4.2.6");
        PublicationFixture publication = new PublicationFixture();
        Contract first = contract();

        assertThat(publication.publish(sdk.ai, first)).isEqualTo(PUBLISHED);
        assertThat(sdk.latest).matches("4\\.2\\.7-c[0-9a-f]{24}");
        assertThat(sdk.latest).isEqualTo(first.server.getVersion());
        assertThat(sdk.records).containsKeys("4.2.6", sdk.latest);
        assertThat(sdk.missingExactQueries).isZero();
        assertThat(sdk.releases).isEqualTo(1);
        assertThat(sdk.status()).isEqualTo(RpcClientStatus.RUNNING);
        assertThat(publication.locked).isFalse();

        assertThat(publication.publish(sdk.ai, contract())).isEqualTo(VERIFIED);
        assertThat(sdk.releases).isEqualTo(1);
        assertThat(sdk.missingExactQueries).isZero();
        assertThat(sdk.status()).isEqualTo(RpcClientStatus.RUNNING);
        assertThat(publication.locked).isFalse();
    }

    @Test
    void realSdkForbiddenReadIsPreservedAndPublicationNeverReleases() throws Exception {
        SdkFixture sdk = new SdkFixture();
        sdk.seed("4.2.6");
        sdk.forbidden = true;
        PublicationFixture publication = new PublicationFixture();

        assertThatThrownBy(() -> publication.publish(sdk.ai, contract()))
                .isExactlyInstanceOf(NacosException.class)
                .satisfies(error -> assertThat(((NacosException) error).getErrCode()).isEqualTo(403));
        assertThat(sdk.releases).isZero();
        assertThat(sdk.status()).isEqualTo(RpcClientStatus.RUNNING);
        assertThat(publication.locked).isFalse();
    }

    private static Contract contract() {
        McpServerBasicInfo server = new McpServerBasicInfo();
        server.setName(NAME);
        server.setVersion(ARTIFACT);
        server.setVersionDetail(version(ARTIFACT, true));
        server.setProtocol(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE);
        server.setFrontProtocol(AiConstants.Mcp.OFFICIAL_TRANSPORT_STREAMABLE);
        server.setDescription("Synthetic SDK contract");
        McpServiceRef reference = new McpServiceRef();
        reference.setNamespaceId(NAMESPACE);
        reference.setGroupName("DEFAULT_GROUP");
        reference.setServiceName(NAME);
        reference.setTransportProtocol("http");
        McpServerRemoteServiceConfig remote = new McpServerRemoteServiceConfig();
        remote.setExportPath("/api/mcp");
        remote.setServiceRef(reference);
        server.setRemoteServerConfig(remote);
        McpTool tool = new McpTool();
        tool.setName("synthetic.query");
        tool.setInputSchema(Map.of("type", "object", "properties", Map.of()));
        McpToolSpecification tools = new McpToolSpecification();
        tools.setTools(List.of(tool));
        McpEndpointSpec endpoint = new McpEndpointSpec();
        endpoint.setType(AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF);
        endpoint.setData(Map.of("namespaceId", NAMESPACE, "groupName", "DEFAULT_GROUP", "serviceName", NAME, "transportProtocol", "http"));
        return new Contract(server, tools, endpoint);
    }

    private static ServerVersionDetail version(String value, boolean latest) {
        ServerVersionDetail detail = new ServerVersionDetail();
        detail.setVersion(value);
        detail.setIs_latest(latest);
        return detail;
    }

    private record Contract(McpServerBasicInfo server, McpToolSpecification tools, McpEndpointSpec endpoint) { }

    /** 只隔离 Nacos 客户端构造器，网络请求落在 Connection 替身；SDK 和 RpcClient 状态机不替换。 */
    private static final class SdkFixture {
        final Map<String, McpServerDetailInfo> records = new LinkedHashMap<>();
        final List<Class<?>> requests = new ArrayList<>();
        final InMemoryRpcClient rpc;
        final AiService ai;
        String latest;
        int missingExactQueries;
        int releases;
        int agentReleases;
        boolean forbidden;

        SdkFixture() throws Exception {
            Connection connection = mock(Connection.class,
                    withSettings().useConstructor((RpcClient.ServerInfo) null).defaultAnswer(CALLS_REAL_METHODS));
            connection.setAbilityTable(Map.of(AbilityKey.SERVER_MCP_REGISTRY.getName(), true,
                    AbilityKey.SERVER_AGENT_REGISTRY.getName(), true, AbilityKey.SERVER_AGENT_CARD_V1.getName(), true));
            when(connection.request(any(Request.class), anyLong())).thenAnswer(call -> respond(call.getArgument(0)));
            rpc = new InMemoryRpcClient(connection);
            SecurityProxy security = mock(SecurityProxy.class);
            when(security.getIdentityContext(any())).thenReturn(Map.of());
            AiGrpcClient grpc = mock(AiGrpcClient.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(grpc, "namespaceId", NAMESPACE);
            ReflectionTestUtils.setField(grpc, "requestTimeout", -1L);
            ReflectionTestUtils.setField(grpc, "rpcClient", rpc);
            ReflectionTestUtils.setField(grpc, "securityProxy", security);
            NacosAiService service = mock(NacosAiService.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(service, "grpcClient", grpc);
            ai = service;
        }

        void seed(String value) {
            Contract contract = contract();
            contract.server.setVersion(value);
            contract.server.getVersionDetail().setVersion(value);
            store(contract.server, contract.tools);
        }

        RpcClientStatus status() {
            @SuppressWarnings("unchecked")
            AtomicReference<RpcClientStatus> state = (AtomicReference<RpcClientStatus>)
                    ReflectionTestUtils.getField(rpc, "rpcClientStatus");
            return state.get();
        }

        private Response respond(Request request) {
            requests.add(request.getClass());
            if (request instanceof QueryMcpServerRequest query) {
                QueryMcpServerResponse response = new QueryMcpServerResponse();
                if (forbidden) {
                    response.setErrorInfo(403, "Synthetic forbidden query");
                    return response;
                }
                if (records.isEmpty()) {
                    response.setErrorInfo(404, "Synthetic missing server name");
                    return response;
                }
                String requested = query.getVersion() == null ? latest : query.getVersion();
                if (!records.containsKey(requested)) {
                    missingExactQueries++;
                    // 与服务端缺版本异常经 GrpcRequestAcceptor 转换后的响应类型一致。
                    return ErrorResponse.build(404, "Synthetic missing exact MCP version");
                }
                McpServerDetailInfo detail = JSON.convertValue(records.get(requested), McpServerDetailInfo.class);
                detail.setAllVersions(records.keySet().stream().map(key -> version(key, key.equals(latest))).toList());
                response.setMcpServerDetailInfo(detail);
                return response;
            }
            if (request instanceof ReleaseMcpServerRequest release) {
                releases++;
                store(release.getServerSpecification(), release.getToolSpecification());
                ReleaseMcpServerResponse response = new ReleaseMcpServerResponse();
                response.setMcpId("synthetic-server-id");
                return response;
            }
            if (request instanceof QueryAgentCardRequest) {
                QueryAgentCardResponse response = new QueryAgentCardResponse();
                response.setErrorInfo(404, "Synthetic missing AgentCard version");
                return response;
            }
            if (request instanceof ReleaseAgentCardRequest) {
                agentReleases++;
                return new ReleaseAgentCardResponse();
            }
            throw new AssertionError("Unexpected SDK request " + request.getClass().getSimpleName());
        }

        private void store(McpServerBasicInfo server, McpToolSpecification tools) {
            McpServerDetailInfo detail = JSON.convertValue(server, McpServerDetailInfo.class);
            detail.setToolSpec(JSON.convertValue(tools, McpToolSpecification.class));
            records.put(server.getVersion(), detail);
            latest = server.getVersion();
        }
    }

    /** 不启动 RpcClient.start；仅提供底层 Connection，保留真实 request/retry/status/重连信号逻辑。 */
    private static final class InMemoryRpcClient extends RpcClient {
        InMemoryRpcClient(Connection connection) {
            super(config());
            ReflectionTestUtils.setField(this, "currentConnection", connection);
            ReflectionTestUtils.setField(this, "rpcClientStatus", new AtomicReference<>(RpcClientStatus.RUNNING));
        }

        @Override public ConnectionType getConnectionType() { return ConnectionType.GRPC; }
        @Override public int rpcPortOffset() { return 1000; }
        @Override public Connection connectToServer(ServerInfo serverInfo) {
            throw new AssertionError("SDK regression must never open a network connection");
        }

        private static RpcClientConfig config() {
            RpcClientConfig config = mock(RpcClientConfig.class);
            when(config.name()).thenReturn("synthetic-sdk-rpc");
            when(config.timeOutMills()).thenReturn(1000L);
            when(config.retryTimes()).thenReturn(0);
            return config;
        }
    }

    /** Redis 只验证发布锁边界，不让 SDK 回归测试连接外部 Redis。 */
    private static final class PublicationFixture {
        final AtomicBoolean locked = new AtomicBoolean();
        final McpRegistryPublicationService service;

        PublicationFixture() throws Exception {
            RLock lock = mock(RLock.class);
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenAnswer(call -> locked.compareAndSet(false, true));
            when(lock.isHeldByCurrentThread()).thenAnswer(call -> locked.get());
            doAnswer(call -> { locked.set(false); return null; }).when(lock).unlock();
            RedissonClient redis = mock(RedissonClient.class);
            when(redis.getLock(anyString())).thenReturn(lock);
            Properties properties = new Properties();
            properties.setProperty("version", ARTIFACT);
            StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of(
                    "redis", redis, "build", new BuildProperties(properties)));
            service = new McpRegistryPublicationService(beans.getBeanProvider(RedissonClient.class),
                    new McpReleaseVersion(beans.getBeanProvider(BuildProperties.class)));
        }

        McpRegistryPublicationService.Outcome publish(AiService sdk, Contract contract) throws Exception {
            return service.publishMcp(sdk, "https://registry.example.invalid", NAMESPACE,
                    contract.server, contract.tools, contract.endpoint);
        }
    }
}
