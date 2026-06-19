package iuh.fit.gateway.infrastructure.filter;

import iuh.fit.gateway.infrastructure.external.client.UserClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationHeaderFilter implements GlobalFilter {

    @Value("${gateway.internal-secret}")
    private String gatewaySecret;

    private final ObjectProvider<UserClient> userClient;

    private record UserAccess(
            String role,
            String authorities
    ) {
    }

    @NotNull
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, @NotNull GatewayFilterChain chain) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
                .filter(principal -> principal instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .flatMap(jwtAuth -> {
                    String userId = jwtAuth.getToken().getSubject();
                    String email = jwtAuth.getToken().getClaimAsString("email");
                    String username = jwtAuth.getToken().getClaimAsString("username");
                    String jwtRole = jwtAuth.getToken().getClaimAsString("role");

                    return fetchUserAccess(userId, jwtRole)
                            .flatMap(access -> {
                                if (!isAllowed(exchange, access.role())) {
                                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                    return exchange.getResponse().setComplete();
                                }

                                ServerHttpRequest request = exchange.getRequest()
                                        .mutate()
                                        .header("X-Gateway-Secret", gatewaySecret)
                                        .header("X-User-Id", userId)
                                        .header("X-User-Username", username == null ? "" : username)
                                        .header("X-User-Email", email == null ? "" : email)
                                        .header("X-User-Roles", access.role())
                                        .header("X-User-Authorities", access.authorities())
                                        .build();

                                return chain.filter(
                                        exchange.mutate()
                                                .request(request)
                                                .build()
                                );
                            });
                })
                .switchIfEmpty(
                        chain.filter(
                                exchange.mutate()
                                        .request(
                                                exchange.getRequest()
                                                        .mutate()
                                                        .header("X-Gateway-Secret", gatewaySecret)
                                                        .build()
                                        )
                                        .build()
                        )
                );
    }

    private Mono<UserAccess> fetchUserAccess(String userId, String fallBackRole) {
        return Mono.fromCallable(() -> {
                    UserClient client = userClient.getIfAvailable();
                    return client != null
                            ? client.getUserPermissions(userId)
                            : null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> {
                    if (response != null && response.getData() != null) {
                        String role = normalizeRole(response.getData().getRole());
                        if(role.isBlank())  {
                            role = fallBackRole;
                        }
                        String authorities =
                                response.getData().getPermissions() == null
                                        ? ""
                                        : String.join(",", response.getData().getPermissions());

                        return new UserAccess(role, authorities);
                    }

                    return new UserAccess(fallBackRole, "");
                })
                .defaultIfEmpty(new UserAccess(fallBackRole, ""))
                .onErrorResume(ex -> {
                    log.warn(
                            "Cannot fetch permission of user {} : {}",
                            userId,
                            ex.getMessage()
                    );
                    return Mono.just(new UserAccess(fallBackRole, ""));
                });
    }

    private String normalizeRole(String role) {
        if (role == null) return "";
        return role.replace("ROLE_", "").toUpperCase();
    }

    private boolean isAllowed(ServerWebExchange exchange, String role) {
        String path = exchange.getRequest()
                        .getPath()
                        .pathWithinApplication()
                        .value();

        if (path.matches("^/[^/]+-service/api/v\\d+/admin(/.*)?$")) {
            return "ADMIN".equals(role);
        }

        return true;
    }
}
