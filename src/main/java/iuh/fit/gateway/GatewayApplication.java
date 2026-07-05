package iuh.fit.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;

@SpringBootApplication
@EnableFeignClients
@EnableWebFluxSecurity
public class GatewayApplication {
    private static final Logger log = LoggerFactory.getLogger(GatewayApplication.class);

    @Value("${user.service.url}")
    private String userServiceUrl;
    @Value("${venue.service.url}")
    private String venueServiceUrl;
    @Value("${notification.service.url}")
    private String notificationServiceUrl;
    @Value("${report.service.url}")
    private String reportServiceUrl;

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
                                "userServiceCircuitBreaker",
                                "forward:/fallback/user-service",
                                circuitBreakerEnabled)
                        )
                        .uri(userServiceUrl)
                )

                // Venue Service
                .route("venue-service-route", r -> r
                        .path("/venue-service/**")
                        .filters(f -> commonFilters(
                                f,
                                defaultRateLimiter,
                                ipKeyResolver,
                                "/venue-service",
                                "venueServiceCircuitBreaker",
                                "forward:/fallback/venue-service",
                                circuitBreakerEnabled)
                        )
                        .uri(venueServiceUrl)
                )

                // Notification Service
                .route("notification-service-route", r -> r
                        .path("/notification-service/**")
                        .filters(f -> commonFilters(
                                f,
                                defaultRateLimiter,
                                ipKeyResolver,
                                "/notification-service",
                                "notificationServiceCircuitBreaker",
                                "forward:/fallback/notification-service",
                                circuitBreakerEnabled)
                        )
                        .uri(notificationServiceUrl)
                )

                // Report Service
                .route("report-service-route", r -> r
                        .path("/report-service/**")
                        .filters(f -> commonFilters(
                                f,
                                defaultRateLimiter,
                                ipKeyResolver,
                                "/report-service",
                                "reportServiceCircuitBreaker",
                                "forward:/fallback/report-service",
                                circuitBreakerEnabled)
                        )
                        .uri(reportServiceUrl)
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
