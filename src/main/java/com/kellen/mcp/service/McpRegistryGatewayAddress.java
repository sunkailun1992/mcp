package com.kellen.mcp.service;

import java.net.URI;
import java.util.Locale;

/** AI Registry 的网关入口；不参与 Nacos Naming 实例注册或业务身份认证。 */
record McpRegistryGatewayAddress(URI origin) {
    static McpRegistryGatewayAddress parse(String value) {
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!uri.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
            return new McpRegistryGatewayAddress(new URI(scheme, null, uri.getHost(), port, null, null, null));
        } catch (Exception exception) {
            throw new IllegalArgumentException("MCP_REGISTRY_INVALID_PUBLIC_BASE_URL");
        }
    }

    String scheme() { return origin.getScheme(); }

    String host() { return origin.getHost(); }

    int port() { return origin.getPort() > 0 ? origin.getPort() : "https".equals(scheme()) ? 443 : 80; }

    String endpoint(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("?") || path.contains("#")) {
            throw new IllegalArgumentException("MCP_REGISTRY_INVALID_EXPORT_PATH");
        }
        return origin.toString() + path;
    }
}
