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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class GatewaySecurityConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private final ObjectMapper objectMapper;

    public GatewaySecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(@NonNull ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/fallback/**").permitAll()

                        // Auth service
                        .pathMatchers("/auth-service/actuator/**").permitAll()
                        .pathMatchers("/auth-service/api/v1/auth/**").permitAll()
                        .pathMatchers("/auth-service/internal/**").denyAll()
                        .pathMatchers("/auth-service/callback/**").permitAll()
                        .pathMatchers("/auth-service/**").authenticated()

                        // Venue service
                        .pathMatchers("/venue-service/actuator/**").permitAll()
                        .pathMatchers("/venue-service/internal/**").denyAll()
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
                        .pathMatchers("/storage-service/**").authenticated()

                        // Workflow Service
                        .pathMatchers("/workflow-service/actuator/**").permitAll()
                        .pathMatchers("/workflow-service/**").authenticated()

                        // Audit Service
                        .pathMatchers("/audit-service/actuator/**").permitAll()
                        .pathMatchers("/audit-service/**").authenticated()

                        // Payment service
                        .pathMatchers("/payment-service/actuator/**").permitAll()
                        .pathMatchers("/payment-service/internal/**").denyAll()
                        .pathMatchers("/payment-service/**").authenticated()

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
