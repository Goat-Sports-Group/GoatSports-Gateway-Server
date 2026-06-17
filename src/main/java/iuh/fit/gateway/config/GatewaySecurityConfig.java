package iuh.fit.gateway.config;

import iuh.fit.gateway.dto.base.BaseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.http.server.reactive.ServerHttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
@EnableWebFluxSecurity
@Slf4j
public class GatewaySecurityConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private final String[] whiteList = {
            // Các endpoint về authentication
            "/api/v1/auth/**",
    };

    private final ObjectMapper objectMapper;

    public GatewaySecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                )
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/roles/**").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/venues/**").permitAll()
                        .pathMatchers(whiteList).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(cookieBearerTokenConverter())
                        .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder())));
        return http.build();
    }

    private ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, ex) -> {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            BaseResponse<Object> base = new BaseResponse<>();
            base.setStatusCode(HttpStatus.UNAUTHORIZED.value());
            base.setData(null);
            base.setMessage("Unauthorized");
            base.setError(ex.getClass().getSimpleName());
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
            BaseResponse<Object> base = new BaseResponse<>();
            base.setStatusCode(HttpStatus.FORBIDDEN.value());
            base.setData(null);
            base.setMessage("Forbidden");
            base.setError(denied.getClass().getSimpleName());
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
