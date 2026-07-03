package com.kellen.mcp.config;

import com.kellen.security.SecurityUser;
import com.kellen.utils.auth.JwtUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * MCP OAuth Resource Server 过滤器。
 *
 * <p>公共 {@code SecurityAuthenticationFilter} 负责基础 Bearer JWT 解析，
 * 这里额外校验 MCP 所需的 issuer、audience/resource 和 scope，并把
 * OAuth scope 映射为 MCP 现有权限码。</p>
 */
public class McpOAuthResourceServerFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

    private final McpOAuthResourceServerProperties properties;

    public McpOAuthResourceServerFilter(McpOAuthResourceServerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/mcp");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String authorization = request.getHeader("Authorization");
            if (StringUtils.isBlank(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
                reject(
                        request,
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "",
                        "MCP OAuth Bearer token is required",
                        requiredScopes()
                );
                return;
            }
            Claims claims = JwtUtils.parseJwt(authorization.substring(BEARER_PREFIX.length()));
            if (!issuerAllowed(claims)) {
                reject(
                        request,
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "invalid_token",
                        "MCP OAuth issuer is not allowed",
                        requiredScopes()
                );
                return;
            }
            if (!audienceAllowed(claims)) {
                reject(
                        request,
                        response,
                        HttpServletResponse.SC_UNAUTHORIZED,
                        "invalid_token",
                        "MCP OAuth audience is not allowed",
                        requiredScopes()
                );
                return;
            }
            List<String> authorities = authoritiesFromScopes(scopes(claims));
            if (authorities.isEmpty()) {
                reject(
                        request,
                        response,
                        HttpServletResponse.SC_FORBIDDEN,
                        "insufficient_scope",
                        "MCP OAuth scope is not allowed",
                        requiredScopes()
                );
                return;
            }
            SecurityUser user = new SecurityUser(
                    firstNotBlank(claims.get("userId", String.class), claims.getSubject()),
                    firstNotBlank(claims.get("username", String.class), claims.get("client_id", String.class)),
                    claims.get("tenantId", String.class),
                    authorities
            );
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    authorities.stream().map(SimpleGrantedAuthority::new).toList()
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            reject(
                    request,
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "invalid_token",
                    "MCP OAuth token is invalid",
                    requiredScopes()
            );
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletRequest request,
                        HttpServletResponse response,
                        int status,
                        String error,
                        String description,
                        String scope) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, bearerChallenge(request, error, description, scope));
        response.setStatus(status);
        response.flushBuffer();
    }

    private String bearerChallenge(HttpServletRequest request, String error, String description, String scope) {
        List<String> parameters = new ArrayList<>();
        parameters.add("resource_metadata=\"" + escapeHeaderValue(protectedResourceMetadataUrl(request)) + "\"");
        if (StringUtils.isNotBlank(scope)) {
            parameters.add("scope=\"" + escapeHeaderValue(scope) + "\"");
        }
        if (StringUtils.isNotBlank(error)) {
            parameters.add("error=\"" + escapeHeaderValue(error) + "\"");
        }
        if (StringUtils.isNotBlank(description)) {
            parameters.add("error_description=\"" + escapeHeaderValue(description) + "\"");
        }
        return "Bearer " + String.join(", ", parameters);
    }

    private String protectedResourceMetadataUrl(HttpServletRequest request) {
        return endpoint(baseUrl(request), PROTECTED_RESOURCE_METADATA_PATH);
    }

    private String baseUrl(HttpServletRequest request) {
        if (StringUtils.isNotBlank(properties.externalBaseUrl())) {
            return StringUtils.removeEnd(properties.externalBaseUrl(), "/");
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        if (StringUtils.isNotBlank(forwardedHost)) {
            String proto = StringUtils.defaultIfBlank(forwardedProto, request.getScheme());
            return proto + "://" + forwardedHost;
        }
        return request.getScheme() + "://" + request.getServerName() + portPart(request.getScheme(), request.getServerPort());
    }

    private String portPart(String scheme, int port) {
        if (("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443)) {
            return "";
        }
        return ":" + port;
    }

    private String endpoint(String baseUrl, String path) {
        return UriComponentsBuilder.fromUriString(StringUtils.removeEnd(baseUrl, "/"))
                .path(path)
                .build()
                .toUriString();
    }

    private String escapeHeaderValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String requiredScopes() {
        return properties.readScope() + " " + properties.callScope();
    }

    private boolean issuerAllowed(Claims claims) {
        return StringUtils.isBlank(properties.issuer()) || properties.issuer().equals(claims.getIssuer());
    }

    private boolean audienceAllowed(Claims claims) {
        Set<String> allowedAudiences = splitValues(properties.audiences());
        if (allowedAudiences.isEmpty()) {
            return true;
        }
        Set<String> tokenAudiences = new LinkedHashSet<>();
        addValue(tokenAudiences, claims.getAudience());
        addValue(tokenAudiences, claims.get("aud"));
        addValue(tokenAudiences, claims.get("resource"));
        return tokenAudiences.stream().anyMatch(allowedAudiences::contains);
    }

    private Set<String> scopes(Claims claims) {
        Set<String> scopes = new LinkedHashSet<>();
        addValue(scopes, claims.get("scope"));
        addValue(scopes, claims.get("scp"));
        return scopes;
    }

    private List<String> authoritiesFromScopes(Set<String> scopes) {
        List<String> authorities = new ArrayList<>();
        if (scopes.contains(properties.readScope())) {
            authorities.add("mcp:tool:list");
        }
        if (scopes.contains(properties.callScope())) {
            authorities.add("mcp:tool:call");
        }
        return authorities;
    }

    private Set<String> splitValues(String raw) {
        Set<String> values = new LinkedHashSet<>();
        addValue(values, raw);
        return values;
    }

    private void addValue(Set<String> values, Object raw) {
        if (raw == null) {
            return;
        }
        if (raw instanceof Collection<?> collection) {
            collection.stream().filter(Objects::nonNull).forEach(value -> addValue(values, value));
            return;
        }
        String.valueOf(raw).replace("[", "").replace("]", "").lines()
                .flatMap(line -> List.of(line.split("[,\\s]+")).stream())
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .forEach(values::add);
    }

    private String firstNotBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }
}
