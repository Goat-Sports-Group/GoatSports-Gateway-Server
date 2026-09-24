package iuh.fit.gateway.infrastructure.filter;

import iuh.fit.gateway.infrastructure.external.client.AuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AuthenticationHeaderFilterTest {

    @Test
    void runsBeforeTheGatewayWebSocketRoutingFilter() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthClient> authClient = mock(ObjectProvider.class);
        AuthenticationHeaderFilter filter = new AuthenticationHeaderFilter(
                authClient,
                mock(ReactiveStringRedisTemplate.class));

        assertThat(filter.getOrder()).isLessThan(Ordered.LOWEST_PRECEDENCE - 1);
    }

    @Test
    void invokesTheGatewayChainOnlyOnceForAnAnonymousRequest() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthClient> authClient = mock(ObjectProvider.class);
        AuthenticationHeaderFilter filter = new AuthenticationHeaderFilter(
                authClient,
                mock(ReactiveStringRedisTemplate.class));
        ReflectionTestUtils.setField(filter, "gatewaySecret", "test-secret");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/auth-service/api/v1/auth/login").build());
        AtomicInteger invocations = new AtomicInteger();
        GatewayFilterChain chain = filteredExchange -> {
            invocations.incrementAndGet();
            assertThat(filteredExchange.getRequest().getHeaders().getFirst("X-Gateway-Secret"))
                    .isEqualTo("test-secret");
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(invocations).hasValue(1);
    }

    /**
     * Service phía sau tin các header X-User-* khi có X-Gateway-Secret. Nếu gateway chuyển tiếp nguyên header
     * do client tự gửi, một request không đăng nhập vào đường dẫn công khai có thể mạo danh bất kỳ ai (kể cả ADMIN).
     */
    @Test
    void stripsClientSuppliedIdentityHeadersFromAnonymousRequests() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthClient> authClient = mock(ObjectProvider.class);
        AuthenticationHeaderFilter filter = new AuthenticationHeaderFilter(
                authClient,
                mock(ReactiveStringRedisTemplate.class));
        ReflectionTestUtils.setField(filter, "gatewaySecret", "test-secret");

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/auth-service/api/v1/auth/me")
                        .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                        .header("X-User-Email", "victim@example.com")
                        .header("X-User-Username", "victim")
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-Authorities", "MANAGE_USERS")
                        .header("X-Gateway-Secret", "forged")
                        .build());
        GatewayFilterChain chain = filteredExchange -> {
            var headers = filteredExchange.getRequest().getHeaders();
            assertThat(headers.getFirst("X-User-Id")).isNull();
            assertThat(headers.getFirst("X-User-Email")).isNull();
            assertThat(headers.getFirst("X-User-Username")).isNull();
            assertThat(headers.getFirst("X-User-Roles")).isNull();
            assertThat(headers.getFirst("X-User-Authorities")).isNull();
            assertThat(headers.get("X-Gateway-Secret")).containsExactly("test-secret");
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();
    }
}
