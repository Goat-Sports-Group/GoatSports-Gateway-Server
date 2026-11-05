package iuh.fit.gateway.infrastructure.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Date;

@WebFluxTest(
        controllers = GatewayVenueSecurityConfigTest.ProbeController.class,
        properties = {
                "app.jwt.secret=" + GatewayVenueSecurityConfigTest.JWT_SECRET,
                "security.cors.allowed-origins=http://localhost:4300",
                "server.port=0",
                "spring.http.codecs.max-in-memory-size=1MB"
        }
)
@ContextConfiguration(classes = {
        CorsConfig.class,
        GatewaySecurityConfig.class,
        GatewayVenueSecurityConfigTest.ProbeController.class
})
class GatewayVenueSecurityConfigTest {
    static final String JWT_SECRET = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void publicVenueReadsDoNotRequireLogin() {
        webTestClient.get().uri("/venue-service/api/v1/venues")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/venue-service/api/v1/venues/venue-id/courts")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/venue-service/api/v1/venue-courts/court-id")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void payOsWebhookDoesNotRequireLogin() {
        webTestClient.post()
                .uri("/payment-service/api/v1/payments/providers/payos/webhook")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void publicStorageFileUrlDoesNotRequireLogin() {
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/storage-service/api/v1/files")
                        .queryParam("key", "venues/example.png")
                        .build())
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.post()
                .uri("/storage-service/api/v1/files")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void ownerAndAdminVenueReadsRequireLogin() {
        webTestClient.get().uri("/venue-service/api/v1/owner/venues")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/venue-service/api/v1/admin/venues")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/venue-service/api/v1/owner/venue-courts/court-id/pricing-rules")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/venue-service/api/v1/owner/bookings")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.post().uri("/venue-service/api/v1/owner/check-ins/lookup")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/venue-service/api/v1/owner/check-ins")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri(
                        "/venue-service/api/v1/owner/revenue?fromDate=2026-08-01&toDate=2026-08-31"
                )
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/venue-service/api/v1/owner/reviews")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.post().uri("/venue-service/api/v1/player/reviews")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void publicClubTournamentAndAiCatalogReadsDoNotRequireLogin() {
        webTestClient.get().uri("/club-service/api/v1/clubs/search")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/club-service/api/v1/tournaments/search")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/ai-service/api/v1/ai/recommendations/venues")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.post().uri("/ai-service/api/v1/ai/chatbot/query")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.post().uri("/ai-service/api/v1/ai/chatbot/feedback")
                .exchange()
                .expectStatus().isNoContent();
        // Trang quản trị kiến thức trợ lý: khách không vào được (ai-service còn kiểm role ADMIN).
        webTestClient.get().uri("/ai-service/api/v1/ai/assistant-admin/gaps")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Khách xem bài công khai; bài đã lưu, đăng bài, báo cáo vẫn cần đăng nhập. */
    @Test
    void guestsCanReadTheFeedButNotWriteOrSeeSavedPosts() {
        webTestClient.get().uri("/social-service/api/v1/social/posts")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/social-service/api/v1/social/posts/post-id")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/social-service/api/v1/social/posts/post-id/comments")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/social-service/api/v1/social/posts/tags/trending")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/auth-service/api/v1/users/user-id")
                .exchange()
                .expectStatus().isNoContent();
        webTestClient.get().uri("/auth-service/api/v1/users/search?query=an")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/auth-service/api/v1/users/me/sport-profiles")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/social-service/api/v1/social/posts/saved")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.post().uri("/social-service/api/v1/social/posts")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/social-service/api/v1/social/reports/decisions/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void clubMutationsSocialAndMatchmakingRequireLogin() {
        webTestClient.post().uri("/club-service/api/v1/clubs")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/social-service/api/v1/social/friends")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.get().uri("/social-service/ws")
                .exchange()
                .expectStatus().isUnauthorized();
        webTestClient.post().uri("/ai-service/api/v1/ai/matchmaking/queue")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** Endpoint nội bộ của ai-service: chỉ service gọi thẳng; qua gateway thì kể cả người đã đăng nhập cũng bị chặn. */
    @Test
    void aiServiceInternalEndpointsAreDeniedEvenWithAValidLogin() throws Exception {
        // Gateway chỉ đọc token từ cookie accessToken.
        String token = signedToken();
        webTestClient.post().uri("/ai-service/api/v1/ai/moderation/classify")
                .cookie("accessToken", token)
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.post().uri("/ai-service/api/v1/ai/matchmaking/sessions/session-id/lifecycle-sync")
                .cookie("accessToken", token)
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.post().uri("/ai-service/api/v1/ai/chatbot/knowledge")
                .cookie("accessToken", token)
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.post().uri("/ai-service/api/v1/ai/personalization/posts/rank")
                .cookie("accessToken", token)
                .exchange()
                .expectStatus().isForbidden();
        webTestClient.post().uri("/ai-service/api/v1/ai/matchmaking/queue")
                .cookie("accessToken", token)
                .exchange()
                .expectStatus().isNoContent();
    }

    private static String signedToken() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject("player@example.com")
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        jwt.sign(new MACSigner(Base64.getDecoder().decode(JWT_SECRET)));
        return jwt.serialize();
    }

    @Test
    void downstreamPrometheusMetricsAreNotReachableThroughTheGateway() {
        webTestClient.get().uri("/auth-service/actuator/prometheus")
                .exchange()
                .expectStatus().is4xxClientError();
        webTestClient.get().uri("/auth-service/actuator/health")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void unauthorizedResponseKeepsCorsHeadersForTokenRefreshHandling() {
        webTestClient.mutate()
                .baseUrl("http://localhost:7070")
                .build()
                .get()
                .uri("/venue-service/api/v1/owner/venues")
                .header("Origin", "http://localhost:4300")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:4300")
                .expectHeader().valueEquals("Access-Control-Allow-Credentials", "true");
    }

    @RestController
    static class ProbeController {
        @GetMapping({
                "/venue-service/api/v1/venues",
                "/venue-service/api/v1/venues/{venueId}/courts",
                "/venue-service/api/v1/venue-courts/{courtId}",
                "/venue-service/api/v1/owner/venues",
                "/venue-service/api/v1/owner/venue-courts/{courtId}/pricing-rules",
                "/venue-service/api/v1/owner/bookings",
                "/venue-service/api/v1/owner/check-ins",
                "/venue-service/api/v1/owner/revenue",
                "/venue-service/api/v1/owner/reviews",
                "/venue-service/api/v1/admin/venues",
                "/club-service/api/v1/clubs/search",
                "/club-service/api/v1/tournaments/search",
                "/ai-service/api/v1/ai/recommendations/venues",
                "/storage-service/api/v1/files",
                "/social-service/api/v1/social/friends",
                "/auth-service/api/v1/users/{userId}",
                "/auth-service/api/v1/users/search",
                "/auth-service/api/v1/users/me/sport-profiles",
                "/social-service/api/v1/social/posts",
                "/social-service/api/v1/social/posts/{postId}",
                "/social-service/api/v1/social/posts/{postId}/comments",
                "/social-service/api/v1/social/posts/tags/trending",
                "/social-service/api/v1/social/reports/decisions/me",
                "/social-service/ws",
                "/auth-service/actuator/prometheus",
                "/auth-service/actuator/health"
        })
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void probe() {}

        @PostMapping({
                "/venue-service/api/v1/owner/check-ins/lookup",
                "/venue-service/api/v1/player/reviews",
                "/payment-service/api/v1/payments/providers/payos/webhook",
                "/club-service/api/v1/clubs",
                "/social-service/api/v1/social/posts",
                "/ai-service/api/v1/ai/chatbot/query",
                "/ai-service/api/v1/ai/chatbot/feedback",
                "/ai-service/api/v1/ai/matchmaking/queue",
                "/ai-service/api/v1/ai/moderation/classify",
                "/ai-service/api/v1/ai/chatbot/knowledge",
                "/ai-service/api/v1/ai/matchmaking/sessions/{sessionId}/lifecycle-sync",
                "/ai-service/api/v1/ai/personalization/posts/rank"
        })
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void checkInProbe() {}
    }
}
