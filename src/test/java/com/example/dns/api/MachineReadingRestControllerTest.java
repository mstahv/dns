package com.example.dns.api;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.ApprovedMachine;
import com.example.dns.domain.ApprovedMachineRepository;
import com.example.dns.domain.Competition;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.DnsEntryRepository;
import com.example.dns.domain.MachineReadingRepository;
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
    ApprovedMachineRepository approvedMachineRepository;

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
        approvedMachineRepository.deleteAll();
        dnsEntryRepository.deleteAll();
        dnsService.clearCache();

        competitionRepository.deleteById(PASSWORD);
        var competition = new Competition();
        competition.setCompetitionId(COMPETITION_ID);
        competition.setPassword(PASSWORD);
        competitionRepository.save(competition);
    }

    private void approveMachine(String machineId) {
        var machine = new ApprovedMachine();
        machine.setCompetitionId(COMPETITION_ID);
        machine.setMachineId(machineId);
        machine.setMachineName("Lukija");
        machine.setApproved(true);
        approvedMachineRepository.save(machine);
    }

    private HttpResponse<String> postReading(String password, String machineId, String jsonBody)
            throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/machine-reading"))
                .header("Content-Type", "application/json")
                .header("X-Competition-Password", password)
                .header("X-Machine-Id", machineId)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void approvedMachine_processesReadingAndMarksStarted() throws Exception {
        approveMachine(MACHINE_ID);

        var response = postReading(PASSWORD, MACHINE_ID, "[{\"bib\": 1}]");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"found\""));

        var readings = machineReadingRepository.findTop100ByCompetitionIdOrderByReadAtDesc(COMPETITION_ID);
        assertEquals(1, readings.size());
    }

    @Test
    void unknownMachine_autoRegistersAndBuffersReading() throws Exception {
        var response = postReading(PASSWORD, "new-reader", "[{\"bib\": 1}]");

        assertEquals(200, response.statusCode());

        // Machine auto-registered as unapproved
        var machine = approvedMachineRepository
                .findByCompetitionIdAndMachineId(COMPETITION_ID, "new-reader");
        assertTrue(machine.isPresent(), "Kone pitäisi rekisteröityä automaattisesti");
        assertFalse(machine.get().isApproved(), "Uuden koneen ei pitäisi olla hyväksytty");

        // Reading is logged but runner not marked as started
        var readings = machineReadingRepository.findTop100ByCompetitionIdOrderByReadAtDesc(COMPETITION_ID);
        assertEquals(1, readings.size());
        assertFalse(readings.getFirst().isFound(), "Puskuroidun lukeman found pitäisi olla false");

        assertFalse(dnsService.isStarted(COMPETITION_ID, 1),
                "Hyväksymättömän koneen lukema ei saa merkitä juoksijaa lähteneeksi");
    }

    @Test
    void invalidPassword_returns401() throws Exception {
        var response = postReading("wrong_password", MACHINE_ID, "[{\"bib\": 1}]");

        assertEquals(401, response.statusCode());

        var readings = machineReadingRepository.findTop100ByCompetitionIdOrderByReadAtDesc(COMPETITION_ID);
        assertTrue(readings.isEmpty());
    }

    @Test
    void ccLookup_withApprovedMachine() throws Exception {
        approveMachine(MACHINE_ID);

        var response = postReading(PASSWORD, MACHINE_ID, "[{\"cc\": 99999}]");

        assertEquals(200, response.statusCode());

        var readings = machineReadingRepository.findTop100ByCompetitionIdOrderByReadAtDesc(COMPETITION_ID);
        assertEquals(1, readings.size());
        assertEquals("99999", readings.getFirst().getCc());
    }

    @Test
    void multipleReadingsInOneRequest() throws Exception {
        approveMachine(MACHINE_ID);

        var response = postReading(PASSWORD, MACHINE_ID, "[{\"bib\": 1}, {\"bib\": 99999}]");

        assertEquals(200, response.statusCode());

        var readings = machineReadingRepository.findTop100ByCompetitionIdOrderByReadAtDesc(COMPETITION_ID);
        assertEquals(2, readings.size());
    }

    @Test
    void duplicateReading_loggedTwice() throws Exception {
        approveMachine(MACHINE_ID);

        postReading(PASSWORD, MACHINE_ID, "[{\"bib\": 1}]");
        postReading(PASSWORD, MACHINE_ID, "[{\"bib\": 1}]");

        var readings = machineReadingRepository.findTop100ByCompetitionIdOrderByReadAtDesc(COMPETITION_ID);
        assertEquals(2, readings.size());
    }
}
