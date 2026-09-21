package iuh.fit.gateway.infrastructure.filter;

import iuh.fit.gateway.infrastructure.external.client.AuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

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
}
