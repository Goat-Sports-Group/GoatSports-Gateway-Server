package iuh.fit.gateway.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/actuator/**").permitAll()
                        .pathMatchers("/fallback/**").permitAll()

//                       User service
                        .pathMatchers("/user-service/actuator/prometheus").permitAll()
                        .pathMatchers("/user-service/api/v1/me/**").permitAll()
                        .pathMatchers("/user-service/internal/**").denyAll()
                        .pathMatchers("/user-service/**").authenticated()

//                       Venue service
                        .pathMatchers("/venue-service/actuator/prometheus").permitAll()
                        .pathMatchers("/venue-service/internal/**").denyAll()
                        .pathMatchers("/venue-service/**").authenticated()

//                       Notification service
                        .pathMatchers("/notification-service/actuator/prometheus").permitAll()
                        .pathMatchers("/notification-service/internal/**").denyAll()
                        .pathMatchers("/notification-service/**").authenticated()

//                       Report service
                        .pathMatchers("/report-service/actuator/prometheus").permitAll()
                        .pathMatchers("/report-service/**").authenticated()

//                       Storage Service
                        .pathMatchers("/storage-service/public/**").permitAll()
                        .pathMatchers("/storage-service/internal/**").denyAll()
                        .pathMatchers("/storage-service/**").authenticated()

                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
