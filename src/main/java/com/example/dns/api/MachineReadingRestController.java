package com.example.dns.api;

import com.example.dns.domain.ApprovedMachine;
import com.example.dns.domain.ApprovedMachineRepository;
import com.example.dns.domain.MachineReading;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.DnsService;
import com.example.dns.service.StartListLookupService;
import com.example.dns.service.StartListLookupService.RunnerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/machine-reading")
public class MachineReadingRestController {

    private static final Logger log = LoggerFactory.getLogger(MachineReadingRestController.class);

    private final CompetitionRepository competitionRepository;
    private final ApprovedMachineRepository approvedMachineRepository;
    private final MachineReadingRepository machineReadingRepository;
    private final StartListLookupService startListLookupService;
    private final DnsService dnsService;

    public MachineReadingRestController(CompetitionRepository competitionRepository,
                                        ApprovedMachineRepository approvedMachineRepository,
                                        MachineReadingRepository machineReadingRepository,
                                        StartListLookupService startListLookupService,
                                        DnsService dnsService) {
        this.competitionRepository = competitionRepository;
        this.approvedMachineRepository = approvedMachineRepository;
        this.machineReadingRepository = machineReadingRepository;
        this.startListLookupService = startListLookupService;
        this.dnsService = dnsService;
    }

    public record ReadingRequest(Integer bib, Integer cc) {
    }

    public record ReadingResult(int bib, String startTime, String name, String className, boolean found) {
    }

    @PostMapping
    public ResponseEntity<?> handleReadings(
            @RequestHeader("X-Machine-Id") String machineId,
            @RequestHeader("X-Competition-Password") String password,
            @RequestBody List<ReadingRequest> readings) {

        // Authenticate competition
        var competition = competitionRepository.findById(password);
        if (competition.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Väärä salasana");
        }
        String competitionId = competition.get().getCompetitionId();

        // Auto-register unknown machines (unapproved by default)
        ApprovedMachine machine = approvedMachineRepository
                .findByCompetitionIdAndMachineId(competitionId, machineId)
                .orElseGet(() -> {
                    var newMachine = new ApprovedMachine();
                    newMachine.setCompetitionId(competitionId);
                    newMachine.setMachineId(machineId);
                    newMachine.setMachineName(machineId);
                    newMachine.setApproved(false);
                    return approvedMachineRepository.save(newMachine);
                });

        String machineName = machine.getMachineName();
        boolean approved = machine.isApproved();

        List<ReadingResult> results = new ArrayList<>();

        for (ReadingRequest req : readings) {
            Integer bib = req.bib();
            Integer cc = req.cc();

            // Log every reading regardless of approval
            var reading = new MachineReading();
            reading.setCompetitionId(competitionId);
            reading.setMachineId(machineId);
            reading.setMachineName(machineName);
            reading.setBib(bib);
            reading.setCc(cc != null ? String.valueOf(cc) : null);
            reading.setReadAt(LocalDateTime.now());

            if (!approved) {
                // Buffer: log but don't process
                reading.setFound(false);
                machineReadingRepository.save(reading);
                log.info("Machine reading buffered (unapproved machine={}): bib={}, cc={}", machineName, bib, cc);
                results.add(new ReadingResult(bib != null ? bib : 0, "", "", "", false));
                continue;
            }

            // Lookup runner
            Optional<RunnerInfo> runnerOpt;
            if (bib != null) {
                runnerOpt = startListLookupService.findByBib(competitionId, bib);
            } else if (cc != null) {
                runnerOpt = startListLookupService.findByControlCard(competitionId, String.valueOf(cc));
            } else {
                continue;
            }

            reading.setFound(runnerOpt.isPresent());
            machineReadingRepository.save(reading);

            if (runnerOpt.isPresent()) {
                RunnerInfo runner = runnerOpt.get();
                if (!dnsService.isStarted(competitionId, runner.bibNumber())) {
                    dnsService.markStarted(competitionId, runner.bibNumber(), machineName);
                }
                log.info("Machine reading: {} (bib={}, machine={})", runner.name(), runner.bibNumber(), machineName);
                LocalTime startTime = runner.startTime();
                results.add(new ReadingResult(
                        runner.bibNumber(),
                        startTime != null ? startTime.toString() : "",
                        runner.name(),
                        runner.className(),
                        true));
            } else {
                log.info("Machine reading: runner not found (bib={}, cc={}, machine={})", bib, cc, machineName);
                results.add(new ReadingResult(
                        bib != null ? bib : 0, "", "", "", false));
            }
        }

        return ResponseEntity.ok(results);
    }
}
