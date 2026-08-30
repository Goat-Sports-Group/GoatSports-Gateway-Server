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
                "server.port=0",
                "spring.http.codecs.max-in-memory-size=1MB"
        }
)
@ContextConfiguration(classes = {
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
                "/venue-service/api/v1/admin/venues"
        })
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void probe() {}

        @PostMapping("/venue-service/api/v1/owner/check-ins/lookup")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void checkInProbe() {}
    }
}
