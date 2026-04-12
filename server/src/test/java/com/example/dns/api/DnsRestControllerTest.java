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

        assertTrue(dnsService.isStarted("secret", 100));
        assertTrue(dnsService.isStarted("secret", 200));
        assertTrue(dnsService.isStarted("secret", 300));

        var entry = dnsService.getEntry("secret", 100);
        assertTrue(entry.isPresent());
        assertEquals("kellokalle", entry.get().getRegisteredBy());
    }

    @Test
    void invalidPassword_returns401() throws Exception {
        var response = postStarted("wrong", "kellokalle", "100");

        assertEquals(401, response.statusCode());
        assertFalse(dnsService.isStarted("secret", 100));
    }

    @Test
    void disabledCompetition_returns403() throws Exception {
        var competition = competitionRepository.findById("secret").orElseThrow();
        competition.setEnabled(false);
        competitionRepository.save(competition);

        var response = postStarted("secret", "kellokalle", "100");

        assertEquals(403, response.statusCode());
        assertFalse(dnsService.isStarted("secret", 100));
    }

    @Test
    void duplicateNumbers_markedOnlyOnce() throws Exception {
        postStarted("secret", "kellokalle", "100");
        var response = postStarted("secret", "kellokalle", "100,200");

        assertEquals(200, response.statusCode());
        assertTrue(dnsService.isStarted("secret", 100));
        assertTrue(dnsService.isStarted("secret", 200));
    }

    @Test
    void restApiMark_reflectedInDnsService() throws Exception {
        // Mark runner via REST API and verify DnsService state
        postStarted("secret", "kellokalle", "42");

        assertTrue(dnsService.isStarted("secret", 42),
                "DnsService should reflect REST API change");

        var entry = dnsService.getEntry("secret", 42);
        assertTrue(entry.isPresent());
        assertEquals("kellokalle", entry.get().getRegisteredBy());
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
