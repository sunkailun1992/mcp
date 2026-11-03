package com.kellen.mcp.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 制品版本缺失时保持可观察，但不能伪造可发布版本或按字符串比较新旧。 */
class McpReleaseVersionTest {

    @Test
    void usesTheStandardBuildPropertiesVersion() {
        McpReleaseVersion version = version("4.2.7");
        assertThat(version.displayVersion()).isEqualTo("4.2.7");
        assertThat(version.requireVersion()).isEqualTo("4.2.7");
    }

    @Test
    void missingBuildInformationDisplaysUnknownAndRefusesPublication() {
        McpReleaseVersion version = new McpReleaseVersion(
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class));
        assertThat(version.displayVersion()).isEqualTo("unknown");
        assertThatThrownBy(version::requireVersion)
                .isInstanceOf(IllegalStateException.class).hasMessage("MCP_REGISTRY_INVALID_ARTIFACT_VERSION");
    }

    @Test
    void buildInformationWithoutAVersionAlsoRefusesPublication() {
        var beans = new StaticListableBeanFactory(Map.of("build", new BuildProperties(new Properties())));
        McpReleaseVersion version = new McpReleaseVersion(beans.getBeanProvider(BuildProperties.class));
        assertThat(version.displayVersion()).isEqualTo("unknown");
        assertThatThrownBy(version::requireVersion).hasMessage("MCP_REGISTRY_INVALID_ARTIFACT_VERSION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.0", "v1.0.0", "01.0.0", "1.0.0-SNAPSHOT", "1.0.0-c0123456789abcdef01234567",
            "1.0.0+metadata", " 1.0.0", "1.0.0 ", "1000000000.0.0", "1.-1.0"})
    void refusesAmbiguousOrNonReleaseVersions(String input) {
        assertThatThrownBy(version(input)::requireVersion)
                .isInstanceOf(IllegalStateException.class).hasMessage("MCP_REGISTRY_INVALID_ARTIFACT_VERSION");
    }

    @Test
    void comparesAllThreeNumericComponentsInsteadOfLexicographicOrder() {
        assertThat(McpReleaseVersion.compare("1.0.10", "1.0.9")).isPositive();
        assertThat(McpReleaseVersion.compare("2.0.0", "10.0.0")).isNegative();
        assertThat(McpReleaseVersion.compare("1.10.0", "1.9.999")).isPositive();
        assertThat(McpReleaseVersion.compare("4.2.7", "4.2.7")).isZero();
        assertThatThrownBy(() -> McpReleaseVersion.compare("unknown", "4.2.7"))
                .hasMessage("MCP_REGISTRY_INVALID_ARTIFACT_VERSION");
    }

    private static McpReleaseVersion version(String value) {
        Properties properties = new Properties();
        properties.setProperty("version", value);
        var beans = new StaticListableBeanFactory(Map.of("build", new BuildProperties(properties)));
        return new McpReleaseVersion(beans.getBeanProvider(BuildProperties.class));
    }
}
