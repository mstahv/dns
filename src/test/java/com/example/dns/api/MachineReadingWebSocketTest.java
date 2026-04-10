package com.example.dns.api;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.Competition;
import com.example.dns.domain.CompetitionMachine;
import com.example.dns.domain.CompetitionMachineRepository;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.DnsEntryRepository;
import com.example.dns.domain.Machine;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.domain.MachineRepository;
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
class MachineReadingWebSocketTest {

    private static final String COMPETITION_ID = "2026_viking";
    private static final String PASSWORD = "ws_test_pw";
    private static final String MACHINE_ID = "ws-reader-1";

    @LocalServerPort
    int port;

    @Autowired CompetitionRepository competitionRepository;
    @Autowired MachineRepository machineRepository;
    @Autowired CompetitionMachineRepository competitionMachineRepository;
    @Autowired MachineReadingRepository machineReadingRepository;
    @Autowired DnsEntryRepository dnsEntryRepository;
    @Autowired DnsService dnsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        machineReadingRepository.deleteAll();
        competitionMachineRepository.deleteAll();
        dnsEntryRepository.deleteAll();
        machineRepository.deleteAll();
        dnsService.clearCache();

        competitionRepository.deleteById(PASSWORD);
        var competition = new Competition();
        competition.setCompetitionId(COMPETITION_ID);
        competition.setPassword(PASSWORD);
        competitionRepository.save(competition);
    }

    private void approveMachine(String machineId) {
        var machine = machineRepository.findByMachineId(machineId)
                .orElseGet(() -> {
                    var m = new Machine();
                    m.setMachineId(machineId);
                    m.setMachineName("WS-Lukija");
                    return machineRepository.save(m);
                });
        var cm = new CompetitionMachine();
        cm.setPassword(PASSWORD);
        cm.setMachine(machine);
        cm.setApproved(true);
        competitionMachineRepository.save(cm);
    }

    @Test
    void webSocketAuth_sendsStartListAndProcessesReadings() throws Exception {
        approveMachine(MACHINE_ID);

        var messages = new LinkedBlockingQueue<String>();
        var listener = new QueueListener(messages);

        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/machine-reading"), listener)
                .get(5, TimeUnit.SECONDS);

        // Auth
        ws.sendText("{\"type\":\"auth\",\"machineId\":\"" + MACHINE_ID + "\"}", true);

        // First message: auth response
        JsonNode authNode = readJson(messages);
        assertTrue(authNode.get("ok").asBoolean(), "Auth pitäisi onnistua");

        // Second message: startlist data
        JsonNode startlistNode = readJson(messages);
        assertEquals("startlist", startlistNode.get("type").asText(),
                "Autentikoinnin jälkeen pitäisi tulla lähtölistatiedot");
        assertTrue(startlistNode.has("data"), "Startlist-viestissä pitäisi olla data-kenttä");

        // Send a reading
        ws.sendText("{\"bib\":1}", true);
        JsonNode resultNode = readJson(messages);
        assertTrue(resultNode.isArray(), "Lukeman vastaus pitäisi olla taulukko");

        var readings = machineReadingRepository.findTop100ByPasswordOrderByReadAtDesc(PASSWORD);
        assertEquals(1, readings.size(), "Lukema pitäisi tallentua kantaan");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void startListData_containsControlCardMappings() throws Exception {
        approveMachine(MACHINE_ID);

        var messages = new LinkedBlockingQueue<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/machine-reading"),
                        new QueueListener(messages))
                .get(5, TimeUnit.SECONDS);

        ws.sendText("{\"type\":\"auth\",\"machineId\":\"" + MACHINE_ID + "\"}", true);

        // Skip auth response
        readJson(messages);

        // Startlist data
        JsonNode startlistNode = readJson(messages);
        JsonNode data = startlistNode.get("data");
        assertNotNull(data, "data pitäisi olla olemassa");

        // Check structure: each entry has bib and st
        if (data.size() > 0) {
            var firstEntry = data.fields().next().getValue();
            assertTrue(firstEntry.has("bib"), "Jokaisella merkinnällä pitäisi olla bib");
            assertTrue(firstEntry.has("st"), "Jokaisella merkinnällä pitäisi olla st (lähtöaika)");
        }

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void webSocketWithoutAuth_returnsError() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        var ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/machine-reading"),
                        new QueueListener(messages))
                .get(5, TimeUnit.SECONDS);

        ws.sendText("{\"bib\":1}", true);

        JsonNode node = readJson(messages);
        assertEquals("error", node.get("type").asText(),
                "Pitäisi palauttaa virhe ilman autentikointia");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    private JsonNode readJson(BlockingQueue<String> queue) throws Exception {
        String msg = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(msg, "Vastaus ei saapunut ajoissa");
        return objectMapper.readTree(msg);
    }

    /**
     * WebSocket listener that queues all received messages.
     */
    private static class QueueListener implements WebSocket.Listener {
        private final BlockingQueue<String> queue;
        private final StringBuilder buffer = new StringBuilder();

        QueueListener(BlockingQueue<String> queue) {
            this.queue = queue;
        }

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

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            // ignore
        }
    }
}
