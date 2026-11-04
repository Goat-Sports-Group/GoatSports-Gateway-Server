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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationHeaderFilter implements GlobalFilter, Ordered {

    private static final String ROLE_PERMISSION_CACHE_PREFIX = "PERMISSION:ROLE:v2:";
    /** Danh tính người dùng chỉ gateway được phép đặt (các service đọc chúng trong JwtFilter/GatewayAuthenticationFilter). */
    private static final List<String> IDENTITY_HEADERS = List.of(
            "X-User-Id", "X-User-Username", "X-User-Email", "X-User-Roles", "X-User-Authorities");
    private static final String BLOCKED_USER_PREFIX = "blocked_user:";
    private static final String DELETION_PENDING = "deletion";
    private static final String INACTIVE = "inactive";
    private static final String SESSION_KEY_PREFIX = "user:";
    private static final String SESSION_KEY_SUFFIX = ":session";
    private static final String SESSION_CLAIM = "sid";
    private static final List<String> SESSION_PATHS = List.of("/api/v1/auth/refresh", "/api/v1/auth/logout");

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

        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(jwtAuth -> jwtAuth.isEmpty()
                        ? chain.filter(withGatewayHeaders(exchange, null, null))
                        : filterAuthenticated(exchange, chain, jwtAuth.get()));
    }

    private Mono<Void> filterAuthenticated(
            ServerWebExchange exchange, GatewayFilterChain chain, JwtAuthenticationToken jwtAuth) {
        String userId = jwtAuth.getToken().getSubject();
        String jwtRole = jwtAuth.getToken().getClaimAsString("role");
        Mono<Void> forward = fetchUserAccess(userId, jwtRole)
                .flatMap(access -> chain.filter(withGatewayHeaders(exchange, jwtAuth, access)));

        // refresh/logout phải tới được auth-service: refresh thất bại thì auth-service xóa cookie phiên.
        String path = exchange.getRequest().getPath().value();
        if (SESSION_PATHS.stream().anyMatch(path::endsWith)) {
            return forward;
        }
        String tokenSessionId = jwtAuth.getToken().getClaimAsString(SESSION_CLAIM);
        return redisTemplate.opsForValue()
                .multiGet(List.of(BLOCKED_USER_PREFIX + userId, SESSION_KEY_PREFIX + userId + SESSION_KEY_SUFFIX))
                .flatMap(values -> {
                    String blockedReason = values.get(0);
                    String currentSessionId = values.get(1);
                    if (blockedReason != null) {
                        return reject(exchange, blockedJson(blockedReason));
                    }
                    // Chưa có mã phiên trong Redis (phiên cũ trước khi có "sid"): cho qua để không đăng xuất hàng loạt.
                    if (currentSessionId != null && !currentSessionId.equals(tokenSessionId)) {
                        return reject(exchange,
                                "{\"error\":\"SESSION_REPLACED\",\"message\":\"Tài khoản vừa đăng nhập ở nơi khác.\"}");
                    }
                    return forward;
                });
    }

    /**
     * Access token còn hạn của tài khoản vừa bị khóa hoặc vừa yêu cầu xóa: chặn ngay, client đọc mã lỗi để đăng xuất.
     * auth-service ghi giá trị {@value #DELETION_PENDING} cho tài khoản chờ xóa, {@value #INACTIVE} cho tài khoản bị
     * admin bỏ xác thực; mọi giá trị khác là bị khóa.
     */
    private static String blockedJson(String reason) {
        return switch (reason) {
            case DELETION_PENDING -> "{\"error\":\"ACCOUNT_DELETION_PENDING\",\"message\":\"Tài khoản đang chờ xóa.\"}";
            case INACTIVE -> "{\"error\":\"ACCOUNT_INACTIVE\",\"message\":\"Tài khoản cần được xác thực lại.\"}";
            default -> "{\"error\":\"ACCOUNT_BLOCKED\",\"message\":\"Tài khoản của bạn đã bị khóa.\"}";
        };
    }

    private Mono<Void> reject(ServerWebExchange exchange, String json) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
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
