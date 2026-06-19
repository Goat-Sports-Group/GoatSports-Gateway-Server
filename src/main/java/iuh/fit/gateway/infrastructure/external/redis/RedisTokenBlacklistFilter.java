package iuh.fit.gateway.infrastructure.external.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisTokenBlacklistFilter implements GlobalFilter {

    private final ReactiveStringRedisTemplate redisTemplate;

    private static final List<String> WHITE_LIST = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/login/keycloak",
            "/api/v1/auth/logout",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/verify",
            "/api/v1/auth/resend",

            "/actuator/**"
    );

    @NotNull
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, @NotNull GatewayFilterChain chain) {

        String path = exchange.getRequest().getPath().value();

        if (WHITE_LIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        HttpCookie cookie = exchange.getRequest().getCookies().getFirst("accessToken");

        if (cookie == null) return chain.filter(exchange);

        String token = cookie.getValue();

        return redisTemplate.hasKey("blacklist:" + token)
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.error("==== RETURN 401 FROM BLACKLIST FILTER ====");
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }

                    return chain.filter(exchange);
                });
    }
}
