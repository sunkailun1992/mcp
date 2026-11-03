package com.kellen.mcp.security;

import java.util.Set;

/**
 * 由既有 OAuth 过滤器验签后保存的请求级身份。
 *
 * <p>共享安全过滤器可能重新组装 Authentication，因此 connector 使用此请求属性
 * 保留已经校验的 client 与 scope；它不接受工具参数、普通请求头或序列化身份。</p>
 *
 * @param clientId OAuth 客户端
 * @param userId JWT 中的使用者或受控机器主体
 * @param tenantId JWT 中的已验证租户
 * @param scopes 已校验 token 的 scope 集合
 */
public record McpVerifiedCaller(String clientId, String userId, String tenantId, Set<String> scopes) {
    public static final String REQUEST_ATTRIBUTE = McpVerifiedCaller.class.getName();

    public McpVerifiedCaller {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
