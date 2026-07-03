package com.kellen.mcp.service;

import com.alibaba.nacos.api.PropertyKeyConst;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Nacos AI Registry 注册器共享的小工具。
 */
final class NacosAiRegistrySupport {

    private NacosAiRegistrySupport() {
    }

    static Properties nacosProperties(String serverAddr,
                                      String namespace,
                                      String username,
                                      String password,
                                      String contextPath) {
        Properties nacosProperties = new Properties();
        nacosProperties.setProperty(PropertyKeyConst.SERVER_ADDR, normalizeServerAddr(serverAddr));
        if (StringUtils.hasText(namespace)) {
            nacosProperties.setProperty(PropertyKeyConst.NAMESPACE, namespace.trim());
        }
        if (StringUtils.hasText(username)) {
            nacosProperties.setProperty(PropertyKeyConst.USERNAME, username.trim());
        }
        if (StringUtils.hasText(password)) {
            nacosProperties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        if (StringUtils.hasText(contextPath)) {
            nacosProperties.setProperty(PropertyKeyConst.CONTEXT_PATH, normalizeContextPath(contextPath));
        }
        return nacosProperties;
    }

    static String resolveAdvertiseHost(String configuredHost, Logger log, String propertyName) {
        if (StringUtils.hasText(configuredHost)) {
            return configuredHost.trim();
        }
        String siteLocal = firstSiteLocalIpv4();
        if (siteLocal != null) {
            return siteLocal;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException exception) {
            log.warn("自动探测 MCP 服务 IP 失败，回退 127.0.0.1，建议显式配置 {}。", propertyName);
            return "127.0.0.1";
        }
    }

    static String normalizeServerAddr(String value) {
        String serverAddr = text(value);
        if (!StringUtils.hasText(serverAddr)) {
            return serverAddr;
        }
        if (serverAddr.startsWith("http://") || serverAddr.startsWith("https://")) {
            URI uri = URI.create(serverAddr);
            int port = uri.getPort();
            return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
        }
        return trimTrailingSlash(serverAddr);
    }

    static String normalizeContextPath(String value) {
        String contextPath = text(value);
        while (contextPath.startsWith("/")) {
            contextPath = contextPath.substring(1);
        }
        return trimTrailingSlash(contextPath);
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String textOrDefault(String value, String defaultValue) {
        String text = text(value);
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private static String firstSiteLocalIpv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException exception) {
            return null;
        }
        return null;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
