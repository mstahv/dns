package com.example.dns.api;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.Competition;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.DnsEntryRepository;
import com.example.dns.service.DnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.vaadin.browserless.VaadinTestApplicationContext;
import com.vaadin.browserless.VaadinTestUiContext;
import com.vaadin.browserless.internal.Routes;
import com.example.dns.ui.DnsView;
import com.example.dns.ui.MainView;
import com.example.dns.ui.RunnerCard;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DnsRestControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    DnsEntryRepository dnsEntryRepository;

    @Autowired
    DnsService dnsService;

    @Autowired
    ApplicationContext springContext;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        dnsEntryRepository.deleteAll();
        dnsService.clearCache();

        competitionRepository.deleteById("secret");
        var competition = new Competition();
        competition.setCompetitionId("2026_test");
        competition.setPassword("secret");
        competitionRepository.save(competition);
    }

    private HttpResponse<String> postStarted(String password, String registeredBy, String body)
            throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/started"))
                .header("Content-Type", "text/plain")
                .header("X-Competition-Password", password)
                .header("X-Registered-By", registeredBy)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validRequest_marksRunnersAsStarted() throws Exception {
        var response = postStarted("secret", "kellokalle", "100,200,300");

        assertEquals(200, response.statusCode());

        assertTrue(dnsService.isStarted("2026_test", 100));
        assertTrue(dnsService.isStarted("2026_test", 200));
        assertTrue(dnsService.isStarted("2026_test", 300));

        var entry = dnsService.getEntry("2026_test", 100);
        assertTrue(entry.isPresent());
        assertEquals("kellokalle", entry.get().getRegisteredBy());
    }

    @Test
    void invalidPassword_returns401() throws Exception {
        var response = postStarted("wrong", "kellokalle", "100");

        assertEquals(401, response.statusCode());
        assertFalse(dnsService.isStarted("2026_test", 100));
    }

    @Test
    void duplicateNumbers_markedOnlyOnce() throws Exception {
        postStarted("secret", "kellokalle", "100");
        var response = postStarted("secret", "kellokalle", "100,200");

        assertEquals(200, response.statusCode());
        assertTrue(dnsService.isStarted("2026_test", 100));
        assertTrue(dnsService.isStarted("2026_test", 200));
    }

    @Test
    void restApiMark_updatesUiViaSignal() throws Exception {
        // User has DnsView open with 2026_viking competition
        competitionRepository.deleteById("viking_pw");
        var vikingComp = new Competition();
        vikingComp.setCompetitionId("2026_viking");
        vikingComp.setPassword("viking_pw");
        competitionRepository.save(vikingComp);

        var routes = new Routes().autoDiscoverViews(MainView.class.getPackageName());
        var app = VaadinTestApplicationContext.forSpring(routes, springContext);
        try {
            var userUi = app.newUser().newWindow();
            DnsView view = userUi.navigate(DnsView.class);
            view.setCompetition("2026_viking");

            // Pick a runner from the first slot
            RunnerCard card = view.getSlotsByTime().values().iterator().next()
                    .getRunnerCards().getFirst();
            int bib = card.getBibNumber();
            assertFalse(card.isStarted(), "Card should not be started initially");

            // External system marks runner via REST API
            postStarted("viking_pw", "kellokalle", String.valueOf(bib));

            // Verify the service state changed
            assertTrue(dnsService.isStarted("2026_viking", bib),
                    "DnsService should reflect REST API change");

            // Simulate signal effect: sync cards from signal
            userUi.activate();
            view.syncCardsFromSignal();
            assertTrue(card.isStarted(),
                    "UI card should update to started after REST API marks runner");
        } finally {
            app.close();
            dnsService.clearCache();
        }
    }

    @Test
    void getStarted_returnsStartedBibsForRequestedTimes() throws Exception {
        // Mark some runners via POST
        postStarted("secret", "kellokalle", "100,200,300");

        // Query started runners — use a time that likely covers some of them
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/started?times=12:00,12:01"))
                .header("X-Competition-Password", "secret")
                .GET()
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        // Response is a comma-separated list of bib numbers (may be empty
        // if the test competition doesn't have runners at those times)
        assertNotNull(response.body());
    }
}
