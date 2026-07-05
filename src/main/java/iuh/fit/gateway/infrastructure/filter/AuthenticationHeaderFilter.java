package iuh.fit.gateway.infrastructure.filter;

import iuh.fit.gateway.infrastructure.external.client.AuthClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthenticationHeaderFilter implements GlobalFilter {
    private final ObjectProvider<AuthClient> userClient;

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
                    String jwtRole = resolveRoleFromJwt(jwtAuth);

                    return fetchUserAccess(userId, jwtRole)
                        .flatMap(access -> {
                            if (!isAllowed(exchange, access.role())) {
                                return Mono.defer(() -> {
                                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

                                    byte[] bytes = "{\"message\": \"Không có quyền truy cập\"}".getBytes(StandardCharsets.UTF_8);
                                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);

                                    return exchange.getResponse().writeWith(Mono.just(buffer));
                                });
                            }

                            ServerHttpRequest request = exchange.getRequest()
                                .mutate()
                                .header("X-User-KeycloakId", userId)
                                .header("X-User-Email", email)
                                .header("X-User-Role", access.role())
                                .header("X-User-Authorities", access.authorities())
                                .build();

                            return chain.filter(exchange.mutate().request(request).build());
                        });
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    private String resolveRoleFromJwt(JwtAuthenticationToken jwtAuth) {
        List<String> rolePriority = List.of("ADMIN", "VENUE_OWNER", "PLAYER");

        Object realmAccess = jwtAuth.getToken().getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            String role = findFirstKnownRole(map.get("roles"), rolePriority);
            if (!role.isBlank()) {
                return role;
            }
        }

        Object resourceAccess = jwtAuth.getToken().getClaims().get("resource_access");
        if (resourceAccess instanceof Map<?, ?> clients) {
            for (Object clientAccess : clients.values()) {
                if (clientAccess instanceof Map<?, ?> map) {
                    String role = findFirstKnownRole(map.get("roles"), rolePriority);
                    if (!role.isBlank()) {
                        return role;
                    }
                }
            }
        }

        return "";
    }

    private String findFirstKnownRole(Object rolesObject, List<String> rolePriority) {
        if (!(rolesObject instanceof Collection<?> roles)) {
            return "";
        }

        List<String> normalizedRoles = roles.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(this::normalizeRole)
                .toList();

        return rolePriority.stream()
                .filter(normalizedRoles::contains)
                .findFirst()
                .orElse("");
    }

    private Mono<UserAccess> fetchUserAccess(String userId, String fallBackRole) {
        return Mono.fromCallable(() -> {
                    AuthClient client = userClient.getIfAvailable();
                    return client != null ? client.getUserPermissions(userId) : null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> {
                    if (response != null && response.data() != null) {
                        String role = normalizeRole(response.data().role());
                        if(role.isBlank())  {
                            role = fallBackRole;
                        }
                        String authorities =
                                response.data().permissions() == null
                                        ? ""
                                        : String.join(",", response.data().permissions());

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
        String path = exchange.getRequest().getPath().pathWithinApplication().value();

        log.info("PATH = {}", path);
        log.info("ROLE = {}", role);

        if (path.matches("/api/v\\d+/admin(/.*)?$")) {
            return isAdmin(role);
        }

        if (path.startsWith("/api/v\\d+/venues")) {
            return isAdmin(role) || isVenueOwner(role);
        }

        if (path.startsWith("/api/v\\d+/venue-courts")) {
            return isAdmin(role) || isVenueOwner(role);
        }

        return true;
    }

    private boolean isAdmin(String role) {
        return "ADMIN".equals(role);
    }

    private boolean isVenueOwner(String role) {
        return "VENUE_OWNER".equals(role);
    }

}
