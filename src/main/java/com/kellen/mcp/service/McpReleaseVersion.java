package com.kellen.mcp.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * MCP 发现元数据使用的制品版本，独立于 Nacos 中遗留的手工版本键。
 * 构建信息缺失时只影响发现元数据发布，不伪造可用的发布版本。
 */
@Component
public class McpReleaseVersion {

    private static final Pattern RELEASE = Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
    private final BuildProperties build;

    public McpReleaseVersion(ObjectProvider<BuildProperties> buildProperties) {
        this.build = buildProperties.getIfAvailable();
    }

    /** 返回供协议展示的版本；非 Gradle 启动允许明确展示 unknown。 */
    public String displayVersion() {
        return build == null || build.getVersion() == null ? "unknown" : build.getVersion();
    }

    /** 只有确定的三段数字制品版本可以用于不可变 Registry 发布。 */
    public String requireVersion() {
        String version = displayVersion();
        validate(version);
        return version;
    }

    /** 按数字比较发布顺序，防止 1.0.9 被误判为高于 1.0.10。 */
    static int compare(String left, String right) {
        validate(left);
        validate(right);
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < a.length; i++) {
            int order = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }

    private static void validate(String version) {
        if (version == null || !RELEASE.matcher(version).matches()) {
            throw new IllegalStateException("MCP_REGISTRY_INVALID_ARTIFACT_VERSION");
        }
    }
}
