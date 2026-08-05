package iuh.fit.gateway.infrastructure.filter;

import iuh.fit.gateway.application.dto.event.ApiLogEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class ApiAuditLogFilter implements GlobalFilter, Ordered {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final List<String> EXCLUDE_PATHS = List.of(
            "/actuator", "/health", "/prometheus", "/fallback"
    );

    @Value("${spring.kafka.topic.user-log}")
    private String userLogTopic;

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @NotNull
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, @NotNull GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();

        if (EXCLUDE_PATHS.stream().anyMatch(path::contains)) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
                .map(principal -> {
                    if (principal instanceof JwtAuthenticationToken jwtAuth) {
                        return jwtAuth.getToken().getSubject();
                    }
                    return "anonymous";
                })
                .defaultIfEmpty("anonymous")
                .flatMap(userId -> {
                    long startTime = System.currentTimeMillis();

                    return chain.filter(exchange).then(Mono.fromRunnable(() -> {
                        String errorMessage = exchange.getAttribute("errorMessage");
                        long duration = System.currentTimeMillis() - startTime;

                        ServerHttpRequest request = exchange.getRequest();
                        String finalUserId = userId;
                        if ("anonymous".equals(finalUserId)) {
                            String headerUserId = request.getHeaders().getFirst("X-User-Id");
                            if (headerUserId != null && !headerUserId.isBlank()) {
                                finalUserId = headerUserId;
                            }
                        }

                        String method = request.getMethod().name();
                        String queryParams = request.getQueryParams().isEmpty() ? "" : request.getQueryParams().toString();
                        String ipAddress = request.getRemoteAddress() != null ?
                                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";

                        int statusCode = exchange.getResponse().getStatusCode() != null ?
                                exchange.getResponse().getStatusCode().value() : 200;

                        // Skip logging for GET audit-logs to avoid recursive logging of logs
                        if (path.contains("/api/v1/logs")) {
                            return;
                        }

                        ApiLogEvent event = ApiLogEvent.builder()
                                .userId(finalUserId)
                                .method(method)
                                .path(path)
                                .queryParams(queryParams)
                                .ipAddress(ipAddress)
                                .statusCode(statusCode)
                                .duration(duration)
                                .timestamp(LocalDateTime.now())
                                .errorMessage(errorMessage)
                                .build();

                        kafkaTemplate.send(userLogTopic, event)
                                .whenComplete((result, ex) -> {
                                    if (ex != null) {
                                        log.error("Failed to send audit log to Kafka: {}", ex.getMessage());
                                    } else {
                                        log.debug("Sent audit log for {} {} (Status: {}, Duration: {}ms)", method, path, statusCode, duration);
                                    }
                                });
                    }));
                });
    }
}
