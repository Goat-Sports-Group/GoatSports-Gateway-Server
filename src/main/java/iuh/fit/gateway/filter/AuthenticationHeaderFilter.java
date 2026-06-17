package iuh.fit.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AuthenticationHeaderFilter extends AbstractGatewayFilterFactory<Object> {
    private final String gatewaySecret;
    private final ReactiveJwtDecoder jwtDecoder;

    public AuthenticationHeaderFilter(
            ReactiveJwtDecoder jwtDecoder,
            @Value("${gateway.internal-secret}") String gatewaySecret
    ) {
        super(Object.class);
        this.jwtDecoder = jwtDecoder;
        this.gatewaySecret = gatewaySecret;
    }

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
                return chain.filter(exchange);
            }

            ServerHttpRequest.Builder baseBuilder = exchange.getRequest().mutate()
                    .header("X-Gateway-Secret", gatewaySecret);

            String token = null;
            HttpCookie cookie = exchange.getRequest().getCookies().getFirst("accessToken");
            if (cookie != null) {
                token = cookie.getValue();
            }

            if (token == null) {
                ServerHttpRequest mutatedRequest = baseBuilder.build();
                log.info("X-Gateway-Secret");
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            }

            return jwtDecoder.decode(token)
                    .flatMap(jwt -> {
                        String userId = jwt.getSubject();
                        String username = jwt.getClaimAsString("username");
                        String email = jwt.getClaimAsString("email");
                        String role = jwt.getClaimAsString("role");

                        ServerHttpRequest.Builder withClaims = baseBuilder;
                        if (userId != null && role != null) {
                            withClaims = withClaims
                                    .header("X-User-Id", userId)
                                    .header("X-User-Username", username != null ? username : "")
                                    .header("X-User-Email", email != null ? email : "")
                                    .header("X-User-Roles", role);
                        }

                        ServerHttpRequest mutatedRequest = withClaims.build();
                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    })
                    .onErrorResume(e -> {
                        ServerHttpRequest mutatedRequest = baseBuilder.build();
                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    });
        };
    }
}
