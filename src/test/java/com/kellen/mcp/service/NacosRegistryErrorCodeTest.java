package com.kellen.mcp.service;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.runtime.NacosRuntimeException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Registry 告警保留 SDK 数字码，不将原始错误正文带入日志。 */
class NacosRegistryErrorCodeTest {
    @Test
    void checkedAndRuntimeSdkErrorsExposeOnlyTheirOwnNumericCode() {
        assertThat(NacosAiRegistrySupport.publicationErrorCode(new NacosException(403, "synthetic private detail")))
                .isEqualTo("403");
        assertThat(NacosAiRegistrySupport.publicationErrorCode(new NacosRuntimeException(500,
                new NacosException(404, "synthetic private detail")))).isEqualTo("500");
    }

    @Test
    void unknownErrorsNeverExposeTheirMessageAndFixedLocalReasonsRemainReadable() {
        assertThat(NacosAiRegistrySupport.publicationErrorCode(new IllegalStateException("synthetic private detail")))
                .isEqualTo("PUBLICATION_CHECK_FAILED");
        assertThat(NacosAiRegistrySupport.publicationErrorCode(new IllegalStateException("MCP_REGISTRY_LOCK_BUSY")))
                .isEqualTo("MCP_REGISTRY_LOCK_BUSY");
    }
}
