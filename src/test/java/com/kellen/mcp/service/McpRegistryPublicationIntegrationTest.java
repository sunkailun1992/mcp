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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kellen.mcp.config.McpAgentRegistryProperties;
import com.kellen.mcp.config.McpServerRegistryProperties;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static com.kellen.mcp.service.McpRegistryPublicationService.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 真实 Spring 刷新事件与发布业务，只用内存存储隔离 Nacos 和跨实例 Redis 锁。 */
class McpRegistryPublicationIntegrationTest {
    private static final String ADDRESS = "https://registry.example.invalid";
    private static final String NAMESPACE = "fixture-registry-space";
    private static final String BASE = "4.2.7";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void contextRefreshPublishesToolsAndCardUsingArtifactRevisionsAndRestartDoesNotRewrite() {
        RegistryStore store = new RegistryStore();
        try (var first = context(store, BASE, true)) {
            assertThat(first.getBean(McpServerRegistryProperties.class).version()).isEqualTo("1.0.2");
            assertThat(first.getBean(McpAgentRegistryProperties.class).cardVersion()).isEqualTo("1.0.2");
            assertThat(store.latestMcp).matches("4\\.2\\.7-c[0-9a-f]{24}");
            assertThat(store.latestAgent).matches("4\\.2\\.7-c[0-9a-f]{24}");
            McpServerDetailInfo server = store.mcps.get(store.latestMcp);
            assertThat(server.getToolSpec().getTools()).extracting(tool -> tool.getName())
                    .containsExactlyInAnyOrder(
                            "third-party.health-check", "user-profile.tags-by-wechat-name");
            assertThat(store.agents.get(store.latestAgent).getSkills()).extracting(skill -> skill.getId())
                    .containsExactly("mcp-tool-catalog", "mcp-tool-call");
            assertThat(store.agents.get(store.latestAgent).getProtocolVersion()).isEqualTo("1.0");
        }
        String mcpVersion = store.latestMcp;
        String agentVersion = store.latestAgent;
        try (var restarted = context(store, BASE, true)) {
            assertThat(restarted.isActive()).isTrue();
            assertThat(store.mcpReleases.get()).isEqualTo(1);
            assertThat(store.agentReleases.get()).isEqualTo(1);
            assertThat(store.latestMcp).isEqualTo(mcpVersion);
            assertThat(store.latestAgent).isEqualTo(agentVersion);
            assertThat(store.locks.values()).allSatisfy(lock -> assertThat(lock.isLocked()).isFalse());
        }
    }

    @Test
    void disabledRegistrarsNeedNeitherBuildInformationNorNacosNorRedis() {
        RegistryStore store = new RegistryStore();
        try (var disabled = context(store, null, false)) {
            assertThat(disabled.getBean(McpReleaseVersion.class).displayVersion()).isEqualTo("unknown");
            verifyNoInteractions(store.ai, store.redis);
            assertThat(store.mcps).isEmpty();
            assertThat(store.agents).isEmpty();
        }
    }

    @Test
    void schemaAndToolEnabledChangesCreateNewContentRevisionsWithinOneArtifact() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        Contract original = contract(store, BASE, false);
        assertThat(publish(publisher, store, original)).isEqualTo(PUBLISHED);
        String first = original.server.getVersion();
        Contract enabled = contract(store, BASE, true);
        assertThat(publish(publisher, store, enabled)).isEqualTo(PUBLISHED);
        assertThat(enabled.server.getVersion()).isNotEqualTo(first);
        Contract changedSchema = contract(store, BASE, true);
        var schema = new LinkedHashMap<>(changedSchema.tools.getTools().get(0).getInputSchema());
        schema.put("description", "synthetic changed tool input contract");
        changedSchema.tools.getTools().get(0).setInputSchema(schema);
        assertThat(publish(publisher, store, changedSchema)).isEqualTo(PUBLISHED);
        assertThat(changedSchema.server.getVersion()).isNotEqualTo(enabled.server.getVersion());
        assertThat(store.mcps).hasSize(3);
        assertThat(publish(publisher, store, original)).isEqualTo(VERIFIED_NOT_LATEST);
        assertThat(store.latestMcp).isEqualTo(changedSchema.server.getVersion());
        assertThat(store.mcpReleases.get()).isEqualTo(3);
    }

    @Test
    void firstPublicationAndNewRevisionNeverProbeAnAbsentExactVersion() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        assertThat(publish(publisher, store, contract(store, BASE, false))).isEqualTo(PUBLISHED);
        assertThat(publish(publisher, store, contract(store, BASE, true))).isEqualTo(PUBLISHED);
        assertThat(store.missingVersionQueries).isZero();
        assertThat(store.mcpReleases.get()).isEqualTo(2);
    }

    @Test
    void listedHistoricalRevisionStillRequiresExactReadbackAndCannotBeRepublishedWhenMissing() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        Contract first = contract(store, BASE, false);
        publish(publisher, store, first);
        publish(publisher, store, contract(store, BASE, true));
        store.missingExactVersion = first.server.getVersion();
        assertThatThrownBy(() -> publish(publisher, store, first)).hasMessage("MCP_REGISTRY_CONTENT_MISMATCH");
        assertThat(store.mcpReleases.get()).isEqualTo(2);
    }

    @Test
    void mapAndToolIterationOrderDoNotCreateANewRevision() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        Contract first = contract(store, BASE, true);
        publish(publisher, store, first);
        Contract reordered = contract(store, BASE, true);
        reordered.tools.setTools(new ArrayList<>(reordered.tools.getTools()));
        Collections.reverse(reordered.tools.getTools());
        for (var tool : reordered.tools.getTools()) tool.setInputSchema(reverseMap(tool.getInputSchema()));
        reordered.tools.setToolsMeta(reverseMap(reordered.tools.getToolsMeta()));
        reordered.tools.setExtensions(reverseMap(reordered.tools.getExtensions()));
        reordered.endpoint.setData(reverseMap(reordered.endpoint.getData()));
        assertThat(publish(publisher, store, reordered)).isEqualTo(VERIFIED);
        assertThat(reordered.server.getVersion()).isEqualTo(first.server.getVersion());
        assertThat(store.mcpReleases.get()).isEqualTo(1);
    }

    @Test
    void directEndpointReadbackUsesAddressPortAndSchemeDespiteNacosRebuiltInternalServiceReference() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        Contract direct = contract(store, BASE, true);
        direct.endpoint.setType(AiConstants.Mcp.MCP_ENDPOINT_TYPE_DIRECT);
        direct.endpoint.setData(Map.of("address", "mcp.example.invalid", "port", "7700", "transportProtocol", "http"));
        assertThat(publish(publisher, store, direct)).isEqualTo(PUBLISHED);
        assertThat(store.mcps.get(store.latestMcp).getRemoteServerConfig().getServiceRef().getServiceName())
                .isEqualTo("synthetic-nacos-generated-service");
        assertThat(publish(publisher, store, direct)).isEqualTo(VERIFIED);
        assertThat(store.mcpReleases.get()).isEqualTo(1);
    }

    @Test
    void httpToHttpsEndpointChangeCreatesANewRevisionAndPreservesTheMcpTransport() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        Contract http = contract(store, BASE, true);
        assertThat(publish(publisher, store, http)).isEqualTo(PUBLISHED);
        Contract https = contract(store, BASE, true);
        https.server.getRemoteServerConfig().getServiceRef().setTransportProtocol("https");
        var data = new LinkedHashMap<>(https.endpoint.getData());
        data.put("transportProtocol", "https");
        https.endpoint.setData(data);
        assertThat(publish(publisher, store, https)).isEqualTo(PUBLISHED);
        assertThat(https.server.getVersion()).isNotEqualTo(http.server.getVersion());
        McpServerDetailInfo actual = store.mcps.get(store.latestMcp);
        assertThat(actual.getFrontProtocol()).isEqualTo("streamable-http");
        assertThat(actual.getRemoteServerConfig().getServiceRef().getTransportProtocol()).isEqualTo("https");
        assertThat(publish(publisher, store, https)).isEqualTo(VERIFIED);
    }

    @Test
    void missingRebuiltServiceReferenceSchemeFailsReadbackForBothEndpointTypes() {
        for (String type : List.of("REF", "DIRECT")) {
            RegistryStore store = new RegistryStore();
            store.missingTransportProtocol = true;
            Contract contract = contract(store, BASE, true);
            if ("DIRECT".equals(type)) {
                contract.endpoint.setType(type);
                contract.endpoint.setData(Map.of("address", "mcp.example.invalid", "port", "7700",
                        "transportProtocol", "http"));
            }
            assertThatThrownBy(() -> publish(publisher(store, BASE), store, contract))
                    .hasMessage("MCP_REGISTRY_CONTENT_MISMATCH");
        }
    }

    @Test
    void directBackendSchemeDifferenceFailsReadbackEvenWhenServiceReferenceMatches() {
        RegistryStore store = new RegistryStore();
        store.wrongBackendProtocol = true;
        Contract direct = contract(store, BASE, true);
        direct.endpoint.setType("DIRECT");
        direct.endpoint.setData(Map.of("address", "mcp.example.invalid", "port", "7700",
                "transportProtocol", "http"));
        assertThatThrownBy(() -> publish(publisher(store, BASE), store, direct))
                .hasMessage("MCP_REGISTRY_CONTENT_MISMATCH");
    }

    @Test
    void emptyAndPublicNamespacesHaveTheSameRevisionAndDistributedLockScope() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        Contract empty = contract(store, BASE, true);
        empty.server.getRemoteServerConfig().getServiceRef().setNamespaceId("");
        var emptyData = new LinkedHashMap<>(empty.endpoint.getData());
        emptyData.put("namespaceId", "");
        empty.endpoint.setData(emptyData);
        assertThat(publisher.publishMcp(store.ai, ADDRESS, "", empty.server, empty.tools, empty.endpoint)).isEqualTo(PUBLISHED);

        Contract publicNamespace = contract(store, BASE, true);
        publicNamespace.server.getRemoteServerConfig().getServiceRef().setNamespaceId("public");
        var publicData = new LinkedHashMap<>(publicNamespace.endpoint.getData());
        publicData.put("namespaceId", "public");
        publicNamespace.endpoint.setData(publicData);
        assertThat(publisher.publishMcp(store.ai, ADDRESS, "public", publicNamespace.server,
                publicNamespace.tools, publicNamespace.endpoint)).isEqualTo(VERIFIED);
        assertThat(publicNamespace.server.getVersion()).isEqualTo(empty.server.getVersion());
        assertThat(store.remoteLocks).hasSize(1);
        assertThat(store.mcpReleases.get()).isEqualTo(1);
        assertThat(store.mcps.get(store.latestMcp).getRemoteServerConfig().getServiceRef().getNamespaceId()).isEqualTo("public");
    }

    @Test
    void registrarRetriesTransientRegistryReadAndStillPublishesEachResourceOnce() {
        RegistryStore store = new RegistryStore();
        store.transientReadFailures = 1;
        try (var context = context(store, BASE, true)) {
            assertThat(context.isActive()).isTrue();
            assertThat(store.transientReadFailures).isZero();
            assertThat(store.mcpReleases.get()).isEqualTo(1);
            assertThat(store.agentReleases.get()).isEqualTo(1);
        }
    }

    @Test
    void repeatedRegistryFailureIsFailSoftAndDoesNotPublishUnverifiedMetadata() {
        RegistryStore store = new RegistryStore();
        store.readError = 403;
        try (var context = context(store, BASE, true)) {
            assertThat(context.isActive()).isTrue();
            assertThat(store.mcpReleases.get()).isZero();
            assertThat(store.agentReleases.get()).isZero();
            assertThat(store.locks.values()).allSatisfy(lock -> assertThat(lock.isLocked()).isFalse());
        }
    }

    @Test
    void olderArtifactDoesNotStealLatestForEitherRegistry() throws Exception {
        RegistryStore store = new RegistryStore();
        Contract newer = contract(store, "4.2.10", true);
        var latest = publisher(store, "4.2.10");
        publish(latest, store, newer);
        publishAgent(latest, store, newer.agent);
        String mcp = store.latestMcp;
        String agent = store.latestAgent;
        var old = publisher(store, "4.2.9");
        Contract older = contract(store, "4.2.9", true);
        assertThat(publish(old, store, older)).isEqualTo(NEWER_VERSION_PRESENT);
        assertThat(publishAgent(old, store, older.agent)).isEqualTo(NEWER_VERSION_PRESENT);
        assertThat(store.latestMcp).isEqualTo(mcp);
        assertThat(store.latestAgent).isEqualTo(agent);
        assertThat(store.mcpReleases.get()).isEqualTo(1);
        assertThat(store.agentReleases.get()).isEqualTo(1);
    }

    @Test
    void nullVersionQueryUsesUniqueAllVersionsLatestInsteadOfLastInsertedVersion() throws Exception {
        RegistryStore store = new RegistryStore();
        Contract newer = contract(store, "4.2.10", true);
        publish(publisher(store, "4.2.10"), store, newer);
        McpServerDetailInfo trailing = copy(store.mcps.get(store.latestMcp), McpServerDetailInfo.class);
        trailing.setVersion("4.2.6-c0123456789abcdef01234567");
        trailing.getVersionDetail().setVersion(trailing.getVersion());
        store.mcps.put(trailing.getVersion(), trailing);
        store.nullQueryReturnsLastInserted = true;
        Contract older = contract(store, BASE, true);
        assertThat(publish(publisher(store, BASE), store, older)).isEqualTo(NEWER_VERSION_PRESENT);
        assertThat(store.latestMcp).isEqualTo(newer.server.getVersion());
        assertThat(store.mcpReleases.get()).isEqualTo(1);
    }

    @Test
    void missingOrMultipleLatestFlagsFailClosed() throws Exception {
        for (int flags : List.of(0, 2)) {
            RegistryStore store = new RegistryStore();
            publish(publisher(store, BASE), store, contract(store, BASE, true));
            store.latestFlagCount = flags;
            assertThatThrownBy(() -> publish(publisher(store, BASE), store, contract(store, BASE, true)))
                    .hasMessage("MCP_REGISTRY_AMBIGUOUS_LATEST");
            assertThat(store.mcpReleases.get()).isEqualTo(1);
        }
    }

    @Test
    void forbiddenRegistryReadIsNotTreatedAsNotFoundForEitherResource() {
        RegistryStore store = new RegistryStore();
        store.readError = 403;
        var publisher = publisher(store, BASE);
        Contract contract = contract(store, BASE, true);
        assertThatThrownBy(() -> publish(publisher, store, contract))
                .isInstanceOfSatisfying(NacosException.class, failure -> assertThat(failure.getErrCode()).isEqualTo(403));
        assertThatThrownBy(() -> publishAgent(publisher, store, contract.agent))
                .isInstanceOfSatisfying(NacosException.class, failure -> assertThat(failure.getErrCode()).isEqualTo(403));
        assertThat(store.mcpReleases.get()).isZero();
        assertThat(store.agentReleases.get()).isZero();
        assertThat(store.locks.values()).allSatisfy(lock -> assertThat(lock.isLocked()).isFalse());
    }

    @Test
    void conflictOnlySucceedsAfterExactContentAndLatestReadbackForBothResources() throws Exception {
        RegistryStore store = new RegistryStore();
        store.conflictAfterStore = true;
        var publisher = publisher(store, BASE);
        Contract contract = contract(store, BASE, true);
        assertThat(publish(publisher, store, contract)).isEqualTo(PUBLISHED);
        assertThat(publishAgent(publisher, store, contract.agent)).isEqualTo(PUBLISHED);
        assertThat(store.mcps).hasSize(1);
        assertThat(store.agents).hasSize(1);
    }

    @Test
    void missingOrPollutedReadbackCannotTurnConflictIntoSuccess() {
        for (String failure : List.of("missing", "polluted")) {
            RegistryStore store = new RegistryStore();
            store.conflictAfterStore = true;
            store.missingReadback = failure.equals("missing");
            store.pollutedReadback = failure.equals("polluted");
            var publisher = publisher(store, BASE);
            Contract contract = contract(store, BASE, true);
            assertThatThrownBy(() -> publish(publisher, store, contract)).hasMessage("MCP_REGISTRY_CONTENT_MISMATCH");
            assertThatThrownBy(() -> publishAgent(publisher, store, contract.agent)).hasMessage("MCP_REGISTRY_CONTENT_MISMATCH");
        }
    }

    @Test
    void successfulReleaseStillRequiresMatchingReadbackAndActualLatest() {
        for (String failure : List.of("missing", "polluted", "latest")) {
            RegistryStore store = new RegistryStore();
            store.missingReadback = failure.equals("missing");
            store.pollutedReadback = failure.equals("polluted");
            store.missingLatest = failure.equals("latest");
            var publisher = publisher(store, BASE);
            Contract contract = contract(store, BASE, true);
            String expected = failure.equals("latest") ? "MCP_REGISTRY_LATEST_READBACK_FAILED" : "MCP_REGISTRY_CONTENT_MISMATCH";
            assertThatThrownBy(() -> publish(publisher, store, contract)).hasMessage(expected);
            assertThatThrownBy(() -> publishAgent(publisher, store, contract.agent)).hasMessage(expected);
        }
    }

    @Test
    void alreadyPublishedContentPollutionIsRejectedWithoutOverwritingIt() throws Exception {
        RegistryStore store = new RegistryStore();
        var publisher = publisher(store, BASE);
        Contract contract = contract(store, BASE, true);
        publish(publisher, store, contract);
        publishAgent(publisher, store, contract.agent);
        store.pollutedReadback = true;
        assertThatThrownBy(() -> publish(publisher, store, contract)).hasMessage("MCP_REGISTRY_CONTENT_MISMATCH");
        assertThatThrownBy(() -> publishAgent(publisher, store, contract.agent)).hasMessage("MCP_REGISTRY_CONTENT_MISMATCH");
        assertThat(store.mcpReleases.get()).isEqualTo(1);
        assertThat(store.agentReleases.get()).isEqualTo(1);
    }

    @Test
    void unavailableBusyLostOrInterruptedLocksNeverPublish() {
        for (String failure : List.of("busy", "down", "lost", "interrupt", "absent")) {
            RegistryStore store = new RegistryStore();
            store.lockFailure = failure;
            var publisher = publisher(store, BASE);
            Contract contract = contract(store, BASE, true);
            var assertion = assertThatThrownBy(() -> publish(publisher, store, contract));
            switch (failure) {
                case "busy" -> assertion.hasMessage("MCP_REGISTRY_LOCK_BUSY");
                case "lost" -> assertion.hasMessage("MCP_REGISTRY_LOCK_LOST");
                case "interrupt" -> assertion.isInstanceOf(InterruptedException.class);
                case "absent" -> assertion.hasMessage("MCP_REGISTRY_LOCK_UNAVAILABLE");
                default -> assertion.hasMessage("synthetic redis unavailable");
            }
            assertThat(store.mcpReleases.get()).isZero();
            assertThat(store.agentReleases.get()).isZero();
            store.releaseTestLocks();
        }
    }

    @Test
    void registrarKeepsInterruptFlagAndDoesNotRetryOrContactNacos() {
        RegistryStore store = new RegistryStore();
        store.lockFailure = "interrupt";
        try (var context = context(store, BASE, true)) {
            assertThat(context.isActive()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(store.ai);
            assertThat(store.lockAttempts.get()).isEqualTo(2);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void concurrentContextRefreshesOfTheSameArtifactPublishEachResourceOnce() throws Exception {
        RegistryStore store = new RegistryStore();
        concurrent(List.of(BASE, BASE, BASE, BASE), base -> {
            try (var context = context(store, base, true)) { assertThat(context.isActive()).isTrue(); }
        });
        assertThat(store.mcpReleases.get()).isEqualTo(1);
        assertThat(store.agentReleases.get()).isEqualTo(1);
        assertThat(store.mcps).hasSize(1);
        assertThat(store.agents).hasSize(1);
        assertThat(store.locks.values()).allSatisfy(lock -> assertThat(lock.isLocked()).isFalse());
    }

    @Test
    void concurrentDifferentArtifactVersionsAlwaysLeaveTheHighestVersionAsLatest() throws Exception {
        RegistryStore store = new RegistryStore();
        concurrent(List.of("4.2.9", "4.2.10", "4.2.8", "4.3.0"), base -> {
            Contract contract = contract(store, base, true);
            var publisher = publisher(store, base);
            publish(publisher, store, contract);
            publishAgent(publisher, store, contract.agent);
        });
        assertThat(store.latestMcp).startsWith("4.3.0-c");
        assertThat(store.latestAgent).startsWith("4.3.0-c");
        assertThat(store.mcps).containsKey(store.latestMcp);
        assertThat(store.agents).containsKey(store.latestAgent);
    }

    private static AnnotationConfigApplicationContext context(RegistryStore store, String base, boolean enabled) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        if (base != null) context.registerBean(BuildProperties.class, () -> build(base));
        context.registerBean(McpServerRegistryProperties.class, () -> serverProperties(enabled));
        context.registerBean(McpAgentRegistryProperties.class, () -> agentProperties(enabled));
        if (enabled) context.registerBean(RedissonClient.class, () -> store.redis);
        context.registerBean(McpReleaseVersion.class);
        context.registerBean(McpRegistryPublicationService.class);
        context.registerBean(McpToolCatalogService.class, () -> catalog(true));
        context.registerBean(McpServerRegistrar.class, () -> {
            var registrar = new McpServerRegistrar(context.getBean(McpServerRegistryProperties.class),
                    context.getBean(McpToolCatalogService.class), 7700, context.getBean(McpReleaseVersion.class),
                    context.getBean(McpRegistryPublicationService.class));
            if (enabled) ReflectionTestUtils.setField(registrar, "aiService", store.ai);
            return registrar;
        });
        context.registerBean(McpAgentCardRegistrar.class, () -> {
            var registrar = new McpAgentCardRegistrar(context.getBean(McpAgentRegistryProperties.class), 7700,
                    context.getBean(McpReleaseVersion.class), context.getBean(McpRegistryPublicationService.class));
            if (enabled) ReflectionTestUtils.setField(registrar, "aiService", store.ai);
            return registrar;
        });
        context.refresh();
        return context;
    }

    private static McpRegistryPublicationService publisher(RegistryStore store, String base) {
        var beans = new StaticListableBeanFactory();
        if (!"absent".equals(store.lockFailure)) beans.addBean("redis", store.redis);
        return new McpRegistryPublicationService(beans.getBeanProvider(RedissonClient.class), release(base));
    }

    private static Contract contract(RegistryStore store, String base, boolean toolEnabled) {
        var version = release(base);
        var publication = publisher(store, base);
        var server = new McpServerRegistrar(serverProperties(true), catalog(toolEnabled), 7700, version, publication);
        var agent = new McpAgentCardRegistrar(agentProperties(true), 7700, version, publication);
        return new Contract(ReflectionTestUtils.invokeMethod(server, "buildServerSpecification", base),
                ReflectionTestUtils.invokeMethod(server, "buildToolSpecification"),
                ReflectionTestUtils.invokeMethod(server, "buildEndpointSpecification"),
                ReflectionTestUtils.invokeMethod(agent, "buildCard", "mcp.example.invalid", 7700, base));
    }

    private static McpToolCatalogService catalog(boolean enabled) {
        return new McpToolCatalogService() {
            @Override
            public java.util.List<com.kellen.mcp.entity.dto.McpToolDescriptor> listTools() {
                return super.listTools().stream().map(tool -> new com.kellen.mcp.entity.dto.McpToolDescriptor(
                        tool.name(), tool.title(), tool.description(), tool.system(), tool.action(), enabled,
                        tool.inputSchemaHints())).toList();
            }
        };
    }

    private static McpServerRegistryProperties serverProperties(boolean enabled) {
        return new McpServerRegistryProperties(enabled, ADDRESS, NAMESPACE, "", "", "/nacos", "mcp", "1.0.2", "",
                "mcp-streamable", "/api/mcp", "REF", "mcp", "DEFAULT_GROUP", "http", "mcp.example.invalid", 0, "");
    }

    private static McpAgentRegistryProperties agentProperties(boolean enabled) {
        return new McpAgentRegistryProperties(enabled, ADDRESS, NAMESPACE, "", "", "/nacos", "mcp.example.invalid", 0,
                false, "1.0", "1.0.2", "", "Synthetic MCP", "mcp-tool-agent", "");
    }

    private static BuildProperties build(String base) {
        Properties properties = new Properties();
        properties.setProperty("version", base);
        return new BuildProperties(properties);
    }

    private static McpReleaseVersion release(String base) {
        var beans = new StaticListableBeanFactory(Map.of("build", build(base)));
        return new McpReleaseVersion(beans.getBeanProvider(BuildProperties.class));
    }

    private static McpRegistryPublicationService.Outcome publish(McpRegistryPublicationService publisher,
            RegistryStore store, Contract contract) throws Exception {
        return publisher.publishMcp(store.ai, ADDRESS, NAMESPACE, contract.server, contract.tools, contract.endpoint);
    }

    private static McpRegistryPublicationService.Outcome publishAgent(McpRegistryPublicationService publisher,
            RegistryStore store, AgentCard card) throws Exception {
        return publisher.publishAgent(store.ai, ADDRESS, NAMESPACE, card);
    }

    private static <T> T copy(Object value, Class<T> type) { return JSON.convertValue(value, type); }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> reverseMap(Map<String, T> source) {
        List<String> keys = new ArrayList<>(source.keySet());
        Collections.reverse(keys);
        Map<String, T> result = new LinkedHashMap<>();
        for (String key : keys) {
            Object value = source.get(key);
            result.put(key, (T) (value instanceof Map<?, ?> map ? reverseMap((Map<String, Object>) map) : value));
        }
        return result;
    }

    private static void concurrent(List<String> versions, Publication action) throws Exception {
        var executor = Executors.newFixedThreadPool(versions.size());
        CountDownLatch ready = new CountDownLatch(versions.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = versions.stream().map(version -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic concurrent start timeout");
                action.run(version);
                return null;
            })).toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface private interface Publication { void run(String base) throws Exception; }
    private record Contract(McpServerBasicInfo server, McpToolSpecification tools, McpEndpointSpec endpoint, AgentCard agent) { }

    /** Nacos 不可变版本存储：同版不覆盖，并保留 SDK 无版本查询与 latest 标识的差异。 */
    private static final class RegistryStore {
        final Map<String, McpServerDetailInfo> mcps = new LinkedHashMap<>();
        final Map<String, AgentCardDetailInfo> agents = new LinkedHashMap<>();
        final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        final Map<String, RLock> remoteLocks = new ConcurrentHashMap<>();
        final AtomicInteger mcpReleases = new AtomicInteger();
        final AtomicInteger agentReleases = new AtomicInteger();
        final AtomicInteger lockAttempts = new AtomicInteger();
        final AiService ai;
        final RedissonClient redis;
        volatile String latestMcp;
        volatile String latestAgent;
        volatile String lockFailure = "";
        boolean missingTransportProtocol;
        boolean wrongBackendProtocol;
        boolean conflictAfterStore;
        boolean missingReadback;
        boolean pollutedReadback;
        boolean missingLatest;
        boolean nullQueryReturnsLastInserted;
        int readError;
        int transientReadFailures;
        int latestFlagCount = 1;
        int missingVersionQueries;
        String missingExactVersion;

        RegistryStore() {
            ai = mock(AiService.class, call -> {
                synchronized (this) {
                    return switch (call.getMethod().getName()) {
                        case "getMcpServer" -> readMcp(call.getArgument(1));
                        case "getAgentCard" -> {
                            assertThat(call.getArguments()).hasSize(3);
                            assertThat(call.getArgument(2, String.class)).isEqualTo(AiConstants.A2a.A2A_ENDPOINT_TYPE_URL);
                            yield readAgent(call.getArgument(1));
                        }
                        case "releaseMcpServer" -> storeMcp(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                        case "releaseAgentCard" -> { storeAgent(call.getArgument(0)); yield null; }
                        case "shutdown" -> null;
                        default -> throw new AssertionError("Unexpected Nacos operation: " + call.getMethod().getName());
                    };
                }
            });
            redis = mock(RedissonClient.class, call -> {
                if (!call.getMethod().getName().equals("getLock")) throw new AssertionError("Unexpected Redis operation");
                if (lockFailure.equals("down")) throw new IllegalStateException("synthetic redis unavailable");
                String key = call.getArgument(0);
                assertThat(key).startsWith("mcp:registry:publish:").doesNotContain(ADDRESS, NAMESPACE);
                return remoteLocks.computeIfAbsent(key, ignored -> remoteLock(key));
            });
        }

        private RLock remoteLock(String key) {
            ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
            return mock(RLock.class, call -> switch (call.getMethod().getName()) {
                case "tryLock" -> {
                    lockAttempts.incrementAndGet();
                    assertThat(call.getArguments()).hasSize(2);
                    assertThat(call.getArgument(0, Long.class)).isEqualTo(10L);
                    assertThat(call.getArgument(1, TimeUnit.class)).isEqualTo(TimeUnit.SECONDS);
                    if (lockFailure.equals("interrupt")) throw new InterruptedException("synthetic interrupted lock");
                    yield !lockFailure.equals("busy") && lock.tryLock(2, TimeUnit.SECONDS);
                }
                case "isHeldByCurrentThread" -> !lockFailure.equals("lost") && lock.isHeldByCurrentThread();
                case "unlock" -> { lock.unlock(); yield null; }
                default -> throw new AssertionError("Unexpected lock operation: " + call.getMethod().getName());
            });
        }

        private McpServerDetailInfo readMcp(String version) throws NacosException {
            if (transientReadFailures > 0) { transientReadFailures--; throw new NacosException(503, "synthetic temporary read failure"); }
            if (readError != 0) throw new NacosException(readError, "synthetic registry read rejected");
            if (mcps.isEmpty()) throw new NacosException(404, "synthetic missing MCP");
            if (missingReadback || version == null && missingLatest) return null;
            String requested = version;
            if (requested == null) requested = nullQueryReturnsLastInserted
                    ? new ArrayList<>(mcps.keySet()).get(mcps.size() - 1) : latestMcp;
            if (!mcps.containsKey(requested) || version != null && version.equals(missingExactVersion)) {
                missingVersionQueries++;
                throw new NacosException(404, "synthetic missing MCP version");
            }
            McpServerDetailInfo result = copy(mcps.get(requested), McpServerDetailInfo.class);
            result.setAllVersions(mcps.keySet().stream().map(key -> {
                ServerVersionDetail detail = new ServerVersionDetail();
                detail.setVersion(key);
                detail.setIs_latest(latestFlagCount > 0 && key.equals(latestMcp));
                return detail;
            }).toList());
            if (latestFlagCount == 2) {
                var flags = new ArrayList<>(result.getAllVersions());
                ServerVersionDetail duplicate = new ServerVersionDetail();
                duplicate.setVersion(latestMcp); duplicate.setIs_latest(true); flags.add(duplicate);
                result.setAllVersions(flags);
            }
            if (missingTransportProtocol) {
                result.getRemoteServerConfig().getServiceRef().setTransportProtocol(null);
            }
            if (wrongBackendProtocol && result.getBackendEndpoints() != null) {
                result.getBackendEndpoints().forEach(endpoint -> endpoint.setProtocol("https"));
            }
            if (pollutedReadback) result.setDescription("synthetic polluted MCP content");
            return result;
        }

        private AgentCardDetailInfo readAgent(String version) throws NacosException {
            if (transientReadFailures > 0) { transientReadFailures--; throw new NacosException(503, "synthetic temporary read failure"); }
            if (readError != 0) throw new NacosException(readError, "synthetic registry read rejected");
            if (agents.isEmpty()) throw new NacosException(404, "synthetic missing AgentCard");
            if (missingReadback || version == null && missingLatest) return null;
            String requested = version == null ? latestAgent : version;
            if (!agents.containsKey(requested)) throw new NacosException(404, "synthetic missing AgentCard version");
            AgentCardDetailInfo result = copy(agents.get(requested), AgentCardDetailInfo.class);
            result.setLatestVersion(requested.equals(latestAgent));
            if (pollutedReadback) result.setDescription("synthetic polluted AgentCard content");
            return result;
        }

        private String storeMcp(McpServerBasicInfo server, McpToolSpecification tools, McpEndpointSpec endpoint) throws NacosException {
            mcpReleases.incrementAndGet();
            String version = server.getVersion();
            if (mcps.containsKey(version)) throw new NacosException(409, "synthetic immutable MCP version");
            McpServerDetailInfo stored = copy(server, McpServerDetailInfo.class);
            stored.setToolSpec(copy(tools, McpToolSpecification.class));
            McpServiceRef reference = new McpServiceRef();
            if (AiConstants.Mcp.MCP_ENDPOINT_TYPE_REF.equals(endpoint.getType())) {
                String namespace = endpoint.getData().get("namespaceId");
                reference.setNamespaceId(namespace == null || namespace.isBlank() ? "public" : namespace);
                reference.setGroupName(endpoint.getData().get("groupName"));
                reference.setServiceName(endpoint.getData().get("serviceName"));
            } else {
                reference.setNamespaceId("public");
                reference.setGroupName("synthetic-nacos-internal-group");
                reference.setServiceName("synthetic-nacos-generated-service");
                McpEndpointInfo backend = new McpEndpointInfo();
                backend.setAddress(endpoint.getData().get("address"));
                backend.setPort(Integer.parseInt(endpoint.getData().get("port")));
                backend.setProtocol(endpoint.getData().get("transportProtocol"));
                stored.setBackendEndpoints(List.of(backend));
            }
            reference.setTransportProtocol(endpoint.getData().get("transportProtocol"));
            stored.getRemoteServerConfig().setServiceRef(reference);
            mcps.put(version, stored);
            latestMcp = version;
            if (conflictAfterStore) throw new NacosException(409, "synthetic competing release completed");
            return "synthetic-mcp-id";
        }

        private void storeAgent(AgentCard card) throws NacosException {
            agentReleases.incrementAndGet();
            String version = card.getVersion();
            if (agents.containsKey(version)) throw new NacosException(409, "synthetic immutable AgentCard version");
            agents.put(version, copy(card, AgentCardDetailInfo.class));
            latestAgent = version;
            if (conflictAfterStore) throw new NacosException(409, "synthetic competing release completed");
        }

        void releaseTestLocks() {
            locks.values().forEach(lock -> { while (lock.isHeldByCurrentThread()) lock.unlock(); });
        }
    }
}
