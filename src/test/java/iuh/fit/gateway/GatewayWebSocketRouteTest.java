package iuh.fit.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SpringCloudCircuitBreakerFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayWebSocketRouteTest {

    @Test
    void notificationWebSocketUsesDedicatedWsUpstream() {
        GatewayApplication application = new GatewayApplication();
        setServiceUrls(application);

        RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
        KeyResolver keyResolver = exchange -> Mono.just("test-client");
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getBean(PathRoutePredicateFactory.class))
                .thenReturn(new PathRoutePredicateFactory(new WebFluxProperties()));
        when(context.getBean(RewritePathGatewayFilterFactory.class))
                .thenReturn(new RewritePathGatewayFilterFactory());
        when(context.getBean(RequestRateLimiterGatewayFilterFactory.class))
                .thenReturn(new RequestRateLimiterGatewayFilterFactory(rateLimiter, keyResolver));
        RouteLocatorBuilder builder = new RouteLocatorBuilder(context);

        @SuppressWarnings("unchecked")
        ObjectProvider<SpringCloudCircuitBreakerFilterFactory> circuitBreakerProvider = mock(ObjectProvider.class);
        when(circuitBreakerProvider.getIfAvailable()).thenReturn(null);

        RouteLocator locator = application.customRouteLocator(
                builder,
                rateLimiter,
                keyResolver,
                circuitBreakerProvider
        );

        Map<String, URI> routes = locator.getRoutes()
                .collectMap(Route::getId, Route::getUri)
                .block();

        assertThat(routes)
                .containsEntry("notification-service-websocket-route", URI.create("ws://localhost:7073"))
                .containsEntry("notification-service-route", URI.create("http://localhost:7073"));
    }

    private static void setServiceUrls(GatewayApplication application) {
        ReflectionTestUtils.setField(application, "authServiceUrl", "http://localhost:7071");
        ReflectionTestUtils.setField(application, "venueServiceUrl", "http://localhost:7072");
        ReflectionTestUtils.setField(application, "notificationServiceUrl", "http://localhost:7073");
        ReflectionTestUtils.setField(application, "notificationServiceWsUrl", "ws://localhost:7073");
        ReflectionTestUtils.setField(application, "reportServiceUrl", "http://localhost:7074");
        ReflectionTestUtils.setField(application, "storageServiceUrl", "http://localhost:7075");
        ReflectionTestUtils.setField(application, "workflowServiceUrl", "http://localhost:7076");
        ReflectionTestUtils.setField(application, "auditServiceUrl", "http://localhost:7077");
        ReflectionTestUtils.setField(application, "paymentServiceUrl", "http://localhost:7078");
        ReflectionTestUtils.setField(application, "clubServiceUrl", "http://localhost:7079");
        ReflectionTestUtils.setField(application, "socialServiceUrl", "http://localhost:7080");
        ReflectionTestUtils.setField(application, "socialServiceWsUrl", "ws://localhost:7080");
        ReflectionTestUtils.setField(application, "aiServiceUrl", "http://localhost:7081");
    }
}
