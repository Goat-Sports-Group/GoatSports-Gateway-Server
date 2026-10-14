package iuh.fit.gateway.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import iuh.fit.gateway.application.dto.response.base.BaseResponse;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class GatewaySecurityConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private final ObjectMapper objectMapper;
    private final CorsConfigurationSource corsConfigurationSource;

    public GatewaySecurityConfig(
            ObjectMapper objectMapper,
            CorsConfigurationSource corsConfigurationSource
    ) {
        this.objectMapper = objectMapper;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(@NonNull ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Metrics cua service phia sau: Prometheus scrape truc tiep trong goatsports-network, khong qua gateway.
                        // Actuator cua chinh gateway nam o management port rieng (7090), cong 7070 khong co /actuator.
                        .pathMatchers("/*/actuator/prometheus").denyAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/fallback/**").permitAll()

                        // Auth service
                        .pathMatchers("/auth-service/actuator/**").permitAll()
                        .pathMatchers("/auth-service/api/v1/auth/**").permitAll()
                        .pathMatchers("/auth-service/internal/**").denyAll()
                        .pathMatchers("/auth-service/callback/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/auth-service/api/v1/support/requests").permitAll()
                        .pathMatchers("/auth-service/**").authenticated()

                        // Venue service
                        .pathMatchers("/venue-service/actuator/**").permitAll()
                        .pathMatchers("/venue-service/internal/**").denyAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/venue-service/api/v1/venues/**",
                                "/venue-service/api/v1/venue-courts/**"
                        ).permitAll()
                        .pathMatchers("/venue-service/**").authenticated()

                        // Notification service
                        .pathMatchers("/notification-service/actuator/**").permitAll()
                        .pathMatchers("/notification-service/internal/**").denyAll()
                        .pathMatchers("/notification-service/**").authenticated()

                        // Report service
                        .pathMatchers("/report-service/actuator/**").permitAll()
                        .pathMatchers("/report-service/**").authenticated()

                        // Storage Service
                        .pathMatchers("/storage-service/actuator/**").permitAll()
                        .pathMatchers("/storage-service/internal/**").denyAll()
                        .pathMatchers(HttpMethod.GET, "/storage-service/api/v1/files").permitAll()
                        .pathMatchers("/storage-service/**").authenticated()

                        // Workflow Service
                        .pathMatchers("/workflow-service/actuator/**").permitAll()
                        .pathMatchers("/workflow-service/**").authenticated()

                        // Audit Service
                        .pathMatchers("/audit-service/actuator/**").permitAll()
                        .pathMatchers("/audit-service/**").authenticated()

                        // Payment service
                        .pathMatchers("/payment-service/actuator/**").permitAll()
                        .pathMatchers(
                                HttpMethod.POST,
                                "/payment-service/api/v1/payments/providers/payos/webhook"
                        ).permitAll()
                        .pathMatchers("/payment-service/internal/**").denyAll()
                        .pathMatchers("/payment-service/**").authenticated()

                        // Club service
                        .pathMatchers("/club-service/actuator/**").permitAll()
                        .pathMatchers("/club-service/internal/**").denyAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/club-service/api/v1/clubs/**",
                                "/club-service/api/v1/tournaments/**"
                        ).permitAll()
                        .pathMatchers("/club-service/**").authenticated()

                        // Social service
                        .pathMatchers("/social-service/actuator/**").permitAll()
                        .pathMatchers("/social-service/internal/**").denyAll()
                        .pathMatchers("/social-service/**").authenticated()

                        // AI service
                        .pathMatchers("/ai-service/health").permitAll()
                        .pathMatchers("/ai-service/api/v1/ai/moderation/**").denyAll()
                        .pathMatchers("/ai-service/api/v1/ai/chatbot/knowledge/**", "/ai-service/api/v1/ai/chatbot/knowledge").denyAll()
                        .pathMatchers("/ai-service/api/v1/ai/matchmaking/sessions/*/lifecycle-sync").denyAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/ai-service/api/v1/ai/recommendations/**"
                        ).permitAll()
                        .pathMatchers(
                                HttpMethod.POST,
                                "/ai-service/api/v1/ai/chatbot/query"
                        ).permitAll()
                        .pathMatchers("/ai-service/**").authenticated()

                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(cookieBearerTokenConverter())
                        .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder())))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()));

        return http.build();
    }

    private ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) -> {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

            BaseResponse<Object> base = new BaseResponse<>(
                    HttpStatus.UNAUTHORIZED.value(),
                    null,
                    ex.getMessage(),
                    ex.getClass().getSimpleName());

            byte[] bytes;
            try {
                bytes = objectMapper.writeValueAsBytes(base);
            } catch (Exception e) {
                bytes = ("{\"statusCode\":401,\"error\":\"serialization_error\"}").getBytes();
            }
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };
    }

    private ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denied) -> {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.FORBIDDEN);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            BaseResponse<Object> base = new BaseResponse<>(
                    HttpStatus.FORBIDDEN.value(),
                    null,
                    denied.getMessage(),
                    denied.getClass().getSimpleName());
            byte[] bytes;
            try {
                bytes = objectMapper.writeValueAsBytes(base);
            } catch (Exception e) {
                bytes = ("{\"statusCode\":403,\"error\":\"serialization_error\"}").getBytes();
            }
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        };
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        SecretKeySpec secretKey = new SecretKeySpec(
                keyBytes,
                "HmacSHA256");
        return NimbusReactiveJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    public ServerAuthenticationConverter cookieBearerTokenConverter() {
        return exchange -> {
            HttpCookie cookie = exchange.getRequest()
                    .getCookies()
                    .getFirst("accessToken");

            if (cookie == null || cookie.getValue().isBlank()) {
                return Mono.empty();
            }

            return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
        };
    }

}
