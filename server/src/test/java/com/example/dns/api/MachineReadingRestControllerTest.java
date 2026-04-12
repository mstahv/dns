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
class MachineReadingRestControllerTest {

    private static final String COMPETITION_ID = "2026_viking";
    private static final String PASSWORD = "machine_pw";
    private static final String MACHINE_ID = "emit-reader-1";

    @LocalServerPort
    int port;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    MachineRepository machineRepository;

    @Autowired
    CompetitionMachineRepository competitionMachineRepository;

    @Autowired
    MachineReadingRepository machineReadingRepository;

    @Autowired
    DnsEntryRepository dnsEntryRepository;

    @Autowired
    DnsService dnsService;

    private final HttpClient http = HttpClient.newHttpClient();

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
                    m.setMachineName("Lukija");
                    return machineRepository.save(m);
                });
        var cm = new CompetitionMachine();
        cm.setPassword(PASSWORD);
        cm.setMachine(machine);
        cm.setApproved(true);
        competitionMachineRepository.save(cm);
    }

    private HttpResponse<String> postReading(String machineId, String jsonBody)
            throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/machine-reading"))
                .header("Content-Type", "application/json")
                .header("X-Machine-Id", machineId)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void approvedMachine_processesReadingAndMarksStarted() throws Exception {
        approveMachine(MACHINE_ID);

        var response = postReading(MACHINE_ID, "[{\"bib\": 1}]");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"found\""));

        var readings = machineReadingRepository.findTop100ByPasswordOrderByReadAtDesc(PASSWORD);
        assertEquals(1, readings.size());
    }

    @Test
    void unknownMachine_returnsEmptyResults() throws Exception {
        // Machine with no competition associations → readings are ignored
        var response = postReading("new-reader", "[{\"bib\": 1}]");

        assertEquals(200, response.statusCode());

        // Global machine auto-created
        var machine = machineRepository.findByMachineId("new-reader");
        assertTrue(machine.isPresent(), "Kone pitäisi luoda automaattisesti");

        // No competition association → no readings stored
        assertEquals("[]", response.body(),
                "Ilman kisayhteyttä ei pitäisi tulla tuloksia");
    }

    @Test
    void unapprovedMachine_buffersReading() throws Exception {
        // Create machine and associate with competition but don't approve
        var machine = new Machine();
        machine.setMachineId(MACHINE_ID);
        machine.setMachineName("Lukija");
        machineRepository.save(machine);

        var cm = new CompetitionMachine();
        cm.setPassword(PASSWORD);
        cm.setMachine(machine);
        cm.setApproved(false);
        competitionMachineRepository.save(cm);

        var response = postReading(MACHINE_ID, "[{\"bib\": 1}]");

        assertEquals(200, response.statusCode());

        var readings = machineReadingRepository.findTop100ByPasswordOrderByReadAtDesc(PASSWORD);
        assertEquals(1, readings.size());
        assertFalse(readings.getFirst().isFound(), "Puskuroidun lukeman found pitäisi olla false");

        assertFalse(dnsService.isStarted(PASSWORD, 1),
                "Hyväksymättömän koneen lukema ei saa merkitä juoksijaa lähteneeksi");
    }

    @Test
    void disabledCompetition_ignoresReadings() throws Exception {
        approveMachine(MACHINE_ID);

        var competition = competitionRepository.findById(PASSWORD).orElseThrow();
        competition.setEnabled(false);
        competitionRepository.save(competition);

        var response = postReading(MACHINE_ID, "[{\"bib\": 1}]");

        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body(),
                "Päättyneen kisan lukemia ei pitäisi käsitellä");
    }

    @Test
    void ccLookup_withApprovedMachine() throws Exception {
        approveMachine(MACHINE_ID);

        var response = postReading(MACHINE_ID, "[{\"cc\": 99999}]");

        assertEquals(200, response.statusCode());

        var readings = machineReadingRepository.findTop100ByPasswordOrderByReadAtDesc(PASSWORD);
        assertEquals(1, readings.size());
        assertEquals("99999", readings.getFirst().getCc());
    }

    @Test
    void multipleReadingsInOneRequest() throws Exception {
        approveMachine(MACHINE_ID);

        var response = postReading(MACHINE_ID, "[{\"bib\": 1}, {\"bib\": 99999}]");

        assertEquals(200, response.statusCode());

        var readings = machineReadingRepository.findTop100ByPasswordOrderByReadAtDesc(PASSWORD);
        assertEquals(2, readings.size());
    }

    @Test
    void duplicateReading_loggedTwice() throws Exception {
        approveMachine(MACHINE_ID);

        postReading(MACHINE_ID, "[{\"bib\": 1}]");
        postReading(MACHINE_ID, "[{\"bib\": 1}]");

        var readings = machineReadingRepository.findTop100ByPasswordOrderByReadAtDesc(PASSWORD);
        assertEquals(2, readings.size());
    }
}
