package iuh.fit.gateway.infrastructure.config;

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

@WebFluxTest(
        controllers = GatewayVenueSecurityConfigTest.ProbeController.class,
        properties = {
                "app.jwt.secret=YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY=",
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

    @Test
    void prometheusMetricsAreNotReachableThroughTheGateway() {
        webTestClient.get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().is4xxClientError();
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
                "/social-service/ws",
                "/actuator/prometheus",
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
                "/ai-service/api/v1/ai/chatbot/query",
                "/ai-service/api/v1/ai/matchmaking/queue"
        })
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void checkInProbe() {}
    }
}
