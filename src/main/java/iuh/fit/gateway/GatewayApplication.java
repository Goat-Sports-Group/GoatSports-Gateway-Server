package iuh.fit.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.GatewayFilterSpec;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableFeignClients
@Slf4j
public class GatewayApplication {

    @Value("${auth.service.url}")
    private String authServiceUrl;

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public RouteLocator customRouteLocator(
            RouteLocatorBuilder builder, RedisRateLimiter defaultRateLimiter, KeyResolver ipKeyResolver,
            ObjectProvider<SpringCloudCircuitBreakerFilterFactory> circuitBreakerFilterFactory
    ) {

        boolean circuitBreakerEnabled = circuitBreakerFilterFactory.getIfAvailable() != null;
        if (!circuitBreakerEnabled) {
            log.warn("Circuit breaker is not available. Circuit breaker filters will be disabled.");
        }

        return builder.routes()
                // User Service
                .route("user-service-route", r -> r
                        .path("/user-service/**")
                        .filters(f -> commonFilters(
                                f,
                                defaultRateLimiter,
                                ipKeyResolver,
                                "/user-service",
                                "authServiceCircuitBreaker",
                                "forward:/fallback/user-service",
                                circuitBreakerEnabled)
                        )
                        .uri(authServiceUrl)
                )
                .build();
    }

    private GatewayFilterSpec commonFilters(
            GatewayFilterSpec filters, RedisRateLimiter defaultRateLimiter,
            KeyResolver ipKeyResolver, String servicePath,
            String circuitBreakerName, String fallbackUri,
            boolean circuitBreakerEnabled
    ) {
        GatewayFilterSpec spec = filters
                .rewritePath(servicePath + "/(?<segment>.*)", "/${segment}")
                .requestRateLimiter(c -> c.setRateLimiter(defaultRateLimiter).setKeyResolver(ipKeyResolver));

        if (!circuitBreakerEnabled) return spec;

        return spec.circuitBreaker(c -> c
                .setName(circuitBreakerName)
                .setFallbackUri(fallbackUri)
                .addStatusCode("500")
                .addStatusCode("502")
                .addStatusCode("503")
                .addStatusCode("504"));
    }
}
