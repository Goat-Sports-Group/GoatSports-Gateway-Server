package iuh.fit.gateway.infrastructure.filter;

import iuh.fit.gateway.infrastructure.external.client.AuthClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.core.Ordered;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationHeaderFilter implements GlobalFilter, Ordered {

    private static final String ROLE_PERMISSION_CACHE_PREFIX = "PERMISSION:ROLE:v2:";
    /** Danh tính người dùng chỉ gateway được phép đặt (các service đọc chúng trong JwtFilter/GatewayAuthenticationFilter). */
    private static final List<String> IDENTITY_HEADERS = List.of(
            "X-User-Id", "X-User-Username", "X-User-Email", "X-User-Roles", "X-User-Authorities");

    @Value("${app.jwt.secret}")
    private String gatewaySecret;

    private final ObjectProvider<AuthClient> userClient;
    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    private record UserAccess(
            String role,
            String authorities) {
    }

    @NotNull
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, @NotNull GatewayFilterChain chain) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        ServerWebExchange gatewayExchange = withGatewayHeaders(exchange, null, null);

        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .flatMap(jwtAuth -> {
                    String userId = jwtAuth.getToken().getSubject();
                    String email = jwtAuth.getToken().getClaimAsString("email");
                    String username = jwtAuth.getToken().getClaimAsString("username");
                    String jwtRole = jwtAuth.getToken().getClaimAsString("role");

                    return fetchUserAccess(userId, jwtRole)
                            .map(access -> withGatewayHeaders(exchange, jwtAuth, access));
                })
                .defaultIfEmpty(gatewayExchange)
                .flatMap(chain::filter);
    }

    private ServerWebExchange withGatewayHeaders(
            ServerWebExchange exchange,
            JwtAuthenticationToken jwtAuth,
            UserAccess access) {
        // BẢO MẬT: các service phía sau tin X-User-* khi request mang X-Gateway-Secret. Luôn xóa bản do client tự gửi,
        // rồi chỉ đặt lại từ JWT đã xác thực — nếu không, request ẩn danh vào đường dẫn công khai (vd GET /auth/me)
        // chỉ cần tự gắn X-User-Id / X-User-Roles là mạo danh được bất kỳ ai.
        ServerHttpRequest.Builder request = exchange.getRequest()
                .mutate()
                .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                .header("X-Gateway-Secret", gatewaySecret);

        if (jwtAuth != null && access != null) {
            String userId = jwtAuth.getToken().getSubject();
            String email = jwtAuth.getToken().getClaimAsString("email");
            String username = jwtAuth.getToken().getClaimAsString("username");
            request.header("X-User-Id", userId)
                    .header("X-User-Username", username == null ? "" : username)
                    .header("X-User-Email", email == null ? "" : email)
                    .header("X-User-Roles", access.role())
                    .header("X-User-Authorities", access.authorities());
        }

        return exchange.mutate().request(request.build()).build();
    }

    private Mono<UserAccess> fetchUserAccess(String userId, String fallBackRole) {
        String role = normalizeRole(fallBackRole);
        String cacheKey = ROLE_PERMISSION_CACHE_PREFIX + role;

        return redisTemplate.opsForValue().get(cacheKey)
                .map(authorities -> {
                    log.info("Cache hit for role {}: {}", role, authorities);
                    return new UserAccess(role, authorities);
                })
                .switchIfEmpty(
                        Mono.defer(() -> {
                            log.info("Cache miss for role {}, loading permissions for user {}", role, userId);
                            return Mono.fromCallable(() -> {
                                AuthClient client = userClient.getIfAvailable();
                                return client != null ? client.getUserPermissions(userId) : null;
                            })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .flatMap(response -> {
                                        if (response != null && response.data() != null) {
                                            String loadedRole = normalizeRole(response.data().role());
                                            if (loadedRole.isBlank()) {
                                                loadedRole = role;
                                            }
                                            String authorities = response.data().permissions() == null
                                                    ? ""
                                                    : String.join(",", response.data().permissions());

                                            String finalRole = loadedRole;
                                            String finalCacheKey = ROLE_PERMISSION_CACHE_PREFIX + finalRole;

                                            return redisTemplate.opsForValue()
                                                    .set(finalCacheKey, authorities, Duration.ofMinutes(15))
                                                    .thenReturn(new UserAccess(finalRole, authorities));
                                        }
                                        return Mono.just(new UserAccess(role, ""));
                                    });
                        }))
                .defaultIfEmpty(new UserAccess(role, ""))
                .onErrorResume(ex -> {
                    log.warn("Cannot fetch permission of user {} : {}", userId, ex.getMessage());
                    return Mono.just(new UserAccess(role, ""));
                });
    }

    private String normalizeRole(String role) {
        if (role == null)
            return "";
        return role.replace("ROLE_", "").toUpperCase();
    }

}
