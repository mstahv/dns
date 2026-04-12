package com.example.dns.api;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.Competition;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.DnsEntryRepository;
import com.example.dns.service.DnsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DnsWebSocketTest {

    private static final String PASSWORD = "ws_dns_pw";

    @LocalServerPort int port;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired DnsEntryRepository dnsEntryRepository;
    @Autowired DnsService dnsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dnsEntryRepository.deleteAll();
        dnsService.clearCache();

        competitionRepository.deleteById(PASSWORD);
        var competition = new Competition();
        competition.setCompetitionId("2026_viking");
        competition.setPassword(PASSWORD);
        competitionRepository.save(competition);
    }

    @Test
    void authAndReceiveCurrentState() throws Exception {
        // Mark some runners before connecting
        dnsService.markStarted(PASSWORD, 100, "test");
        dnsService.markStarted(PASSWORD, 200, "test");

        var messages = new LinkedBlockingQueue<String>();
        var ws = connect(messages);

        ws.sendText("{\"type\":\"auth\",\"password\":\"" + PASSWORD + "\"}", true);

        // Auth response
        JsonNode auth = readJson(messages);
        assertTrue(auth.get("ok").asBoolean());

        // Current state
        JsonNode state = readJson(messages);
        assertEquals("started", state.get("type").asText());
        assertTrue(state.get("bibs").isArray());
        assertEquals(2, state.get("bibs").size(),
                "Pitäisi saada 2 jo lähtenyttä kilpailijaa");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void receivesLiveMarkEvents() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        var ws = connect(messages);

        ws.sendText("{\"type\":\"auth\",\"password\":\"" + PASSWORD + "\"}", true);
        readJson(messages); // auth
        readJson(messages); // initial state

        // Mark a runner from "outside" (simulates UI, REST, or machine reading)
        dnsService.markStarted(PASSWORD, 42, "kellokalle");

        JsonNode event = readJson(messages);
        assertEquals("mark", event.get("type").asText());
        assertEquals(42, event.get("bib").asInt());
        assertEquals("kellokalle", event.get("registeredBy").asText());

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void receivesLiveUnmarkEvents() throws Exception {
        dnsService.markStarted(PASSWORD, 42, "test");

        var messages = new LinkedBlockingQueue<String>();
        var ws = connect(messages);

        ws.sendText("{\"type\":\"auth\",\"password\":\"" + PASSWORD + "\"}", true);
        readJson(messages); // auth
        readJson(messages); // initial state

        dnsService.unmarkStarted(PASSWORD, 42);

        JsonNode event = readJson(messages);
        assertEquals("unmark", event.get("type").asText());
        assertEquals(42, event.get("bib").asInt());

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void wrongPassword_authFails() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        var ws = connect(messages);

        ws.sendText("{\"type\":\"auth\",\"password\":\"wrong\"}", true);

        JsonNode auth = readJson(messages);
        assertFalse(auth.get("ok").asBoolean());

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private WebSocket connect(BlockingQueue<String> messages) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/started"),
                        new QueueListener(messages))
                .get(5, TimeUnit.SECONDS);
    }

    private JsonNode readJson(BlockingQueue<String> queue) throws Exception {
        String msg = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(msg, "Vastaus ei saapunut ajoissa");
        return objectMapper.readTree(msg);
    }

    private static class QueueListener implements WebSocket.Listener {
        private final BlockingQueue<String> queue;
        private final StringBuilder buffer = new StringBuilder();

        QueueListener(BlockingQueue<String> queue) { this.queue = queue; }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                queue.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
