package com.kellen.mcp.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * MCP OAuth 过滤器注册配置。
 */
@Configuration
public class McpOAuthFilterConfig {

    @Bean
    public FilterRegistrationBean<McpOAuthResourceServerFilter> mcpOAuthResourceServerFilter(
            McpOAuthResourceServerProperties properties) {
        FilterRegistrationBean<McpOAuthResourceServerFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpOAuthResourceServerFilter(properties));
        registration.addUrlPatterns("/api/mcp/*", "/api/mcp");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        return registration;
    }
}
