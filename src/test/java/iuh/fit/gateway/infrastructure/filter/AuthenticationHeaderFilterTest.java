package iuh.fit.gateway.infrastructure.filter;

import iuh.fit.gateway.infrastructure.external.client.AuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
            HttpHeaders headers = filteredExchange.getRequest().getHeaders();
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

    /** Admin khóa tài khoản → access token còn hạn của người đó bị chặn ngay, trừ refresh (để auth-service xóa cookie). */
    @Test
    void rejectsLiveTokensOfABlockedAccountButLetsRefreshThrough() {
        String userId = "00000000-0000-0000-0000-0000000000b1";
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just("VIEW_VENUES"));
        stateOf(values, userId, "1", null);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthClient> authClient = mock(ObjectProvider.class);
        AuthenticationHeaderFilter filter = new AuthenticationHeaderFilter(authClient, redis);
        ReflectionTestUtils.setField(filter, "gatewaySecret", "test-secret");
        AtomicInteger invocations = new AtomicInteger();
        GatewayFilterChain chain = filtered -> {
            invocations.incrementAndGet();
            return Mono.empty();
        };

        ServerWebExchange blocked = authenticated("/venue-service/api/v1/venues", userId);
        filter.filter(blocked, chain).block();
        MockServerHttpResponse response = (MockServerHttpResponse) blocked.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBodyAsString().block()).contains("ACCOUNT_BLOCKED");
        assertThat(invocations).hasValue(0);

        filter.filter(authenticated("/auth-service/api/v1/auth/refresh", userId), chain).block();
        assertThat(invocations).hasValue(1);
    }

    /** Người dùng vừa yêu cầu xóa tài khoản: phiên ở thiết bị khác bị chặn với mã riêng, không báo nhầm là bị khóa. */
    @Test
    void tellsPendingDeletionApartFromBlocked() {
        String userId = "00000000-0000-0000-0000-0000000000d1";
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just("VIEW_VENUES"));
        stateOf(values, userId, "deletion", null);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthClient> authClient = mock(ObjectProvider.class);
        AuthenticationHeaderFilter filter = new AuthenticationHeaderFilter(authClient, redis);
        ReflectionTestUtils.setField(filter, "gatewaySecret", "test-secret");

        ServerWebExchange exchange = authenticated("/venue-service/api/v1/venues", userId);
        filter.filter(exchange, filtered -> Mono.empty()).block();

        MockServerHttpResponse response = (MockServerHttpResponse) exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBodyAsString().block()).contains("ACCOUNT_DELETION_PENDING");
    }

    /** Admin bỏ xác thực: phiên còn hạn bị chặn với mã riêng để client báo "cần xác thực lại", không phải "bị khóa". */
    @Test
    void tellsUnverifiedApartFromBlocked() {
        String userId = "00000000-0000-0000-0000-0000000000e1";
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just("VIEW_VENUES"));
        stateOf(values, userId, "inactive", null);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthClient> authClient = mock(ObjectProvider.class);
        AuthenticationHeaderFilter filter = new AuthenticationHeaderFilter(authClient, redis);
        ReflectionTestUtils.setField(filter, "gatewaySecret", "test-secret");

        ServerWebExchange exchange = authenticated("/venue-service/api/v1/venues", userId);
        filter.filter(exchange, filtered -> Mono.empty()).block();

        MockServerHttpResponse response = (MockServerHttpResponse) exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBodyAsString().block()).contains("ACCOUNT_INACTIVE");
    }

    /** Đăng nhập ở nơi khác: access token còn hạn của phiên cũ bị chặn ngay với mã riêng. */
    @Test
    void rejectsTokensOfAReplacedSession() {
        String userId = "00000000-0000-0000-0000-0000000000f1";
        ServerWebExchange exchange = authenticated("/venue-service/api/v1/venues", userId, "old-session");
        AtomicInteger invocations = runWithSession(exchange, userId, "new-session");

        MockServerHttpResponse response = (MockServerHttpResponse) exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBodyAsString().block()).contains("SESSION_REPLACED");
        assertThat(invocations).hasValue(0);
    }

    @Test
    void forwardsTokensOfTheCurrentSession() {
        String userId = "00000000-0000-0000-0000-0000000000f2";
        AtomicInteger invocations = runWithSession(
                authenticated("/venue-service/api/v1/venues", userId, "session-1"), userId, "session-1");
        assertThat(invocations).hasValue(1);
    }

    /** Token phát hành trước khi có "sid" vẫn dùng được cho tới khi tài khoản đăng nhập lại. */
    @Test
    void forwardsLegacyTokensWhileNoSessionIsRecorded() {
        String userId = "00000000-0000-0000-0000-0000000000f3";
        AtomicInteger invocations = runWithSession(
                authenticated("/venue-service/api/v1/venues", userId, null), userId, null);
        assertThat(invocations).hasValue(1);
    }

    private static AtomicInteger runWithSession(ServerWebExchange exchange, String userId, String currentSession) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(Mono.just("VIEW_VENUES"));
        stateOf(values, userId, null, currentSession);
        @SuppressWarnings("unchecked")
        ObjectProvider<AuthClient> authClient = mock(ObjectProvider.class);
        AuthenticationHeaderFilter filter = new AuthenticationHeaderFilter(authClient, redis);
        ReflectionTestUtils.setField(filter, "gatewaySecret", "test-secret");
        AtomicInteger invocations = new AtomicInteger();
        filter.filter(exchange, filtered -> {
            invocations.incrementAndGet();
            return Mono.empty();
        }).block();
        return invocations;
    }

    private static void stateOf(ReactiveValueOperations<String, String> values, String userId,
                                String blockedReason, String currentSession) {
        when(values.multiGet(List.of("blocked_user:" + userId, "user:" + userId + ":session")))
                .thenReturn(Mono.just(Arrays.asList(blockedReason, currentSession)));
    }

    private static ServerWebExchange authenticated(String path, String userId) {
        return authenticated(path, userId, null);
    }

    private static ServerWebExchange authenticated(String path, String userId, String sessionId) {
        Jwt.Builder jwt = Jwt.withTokenValue("token").header("alg", "none").subject(userId).claim("role", "PLAYER");
        if (sessionId != null) {
            jwt.claim("sid", sessionId);
        }
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build())
                .mutate().principal(Mono.just(new JwtAuthenticationToken(jwt.build()))).build();
    }
}
