package com.example.dns.service;

import com.example.dns.domain.CompetitionMachine;
import com.example.dns.domain.CompetitionMachineRepository;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.Machine;
import com.example.dns.domain.MachineReading;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.domain.MachineRepository;
import com.example.dns.service.StartListLookupService.ControlCardEntry;
import com.example.dns.service.StartListLookupService.RunnerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MachineReadingService {

    private static final Logger log = LoggerFactory.getLogger(MachineReadingService.class);

    private final CompetitionRepository competitionRepository;
    private final MachineRepository machineRepository;
    private final CompetitionMachineRepository competitionMachineRepository;
    private final MachineReadingRepository machineReadingRepository;
    private final StartListLookupService startListLookupService;
    private final DnsService dnsService;

    public MachineReadingService(CompetitionRepository competitionRepository,
                                 MachineRepository machineRepository,
                                 CompetitionMachineRepository competitionMachineRepository,
                                 MachineReadingRepository machineReadingRepository,
                                 StartListLookupService startListLookupService,
                                 DnsService dnsService) {
        this.competitionRepository = competitionRepository;
        this.machineRepository = machineRepository;
        this.competitionMachineRepository = competitionMachineRepository;
        this.machineReadingRepository = machineReadingRepository;
        this.startListLookupService = startListLookupService;
        this.dnsService = dnsService;
    }

    public record ReadingRequest(Integer bib, Integer cc) {
    }

    public record ReadingResult(int bib, String startTime, String name, String className, boolean found) {
    }

    /**
     * Finds or creates a global Machine by its device identifier.
     */
    public Machine resolveOrCreateMachine(String machineId) {
        return machineRepository.findByMachineId(machineId)
                .orElseGet(() -> {
                    var m = new Machine();
                    m.setMachineId(machineId);
                    m.setMachineName(machineId);
                    return machineRepository.save(m);
                });
    }

    /**
     * Builds a merged control card map for all enabled, approved competitions
     * this machine is associated with.
     */
    public Map<String, ControlCardEntry> buildStartListDataForMachine(Machine machine) {
        List<CompetitionMachine> associations = competitionMachineRepository.findByMachine(machine);
        Map<String, ControlCardEntry> merged = new LinkedHashMap<>();
        for (CompetitionMachine cm : associations) {
            if (!cm.isApproved()) {
                continue;
            }
            var competition = competitionRepository.findById(cm.getPassword()).orElse(null);
            if (competition == null || !competition.isEnabled()) {
                continue;
            }
            merged.putAll(startListLookupService.buildControlCardMap(competition.getCompetitionId()));
        }
        return merged;
    }

    /**
     * Processes a single reading for all enabled, approved competition associations.
     * Logs the reading for each association regardless of approval.
     */
    public List<ReadingResult> processReading(Machine machine, ReadingRequest req) {
        List<CompetitionMachine> associations = competitionMachineRepository.findByMachine(machine);
        if (associations.isEmpty()) {
            log.info("Machine {} has no competition associations, reading ignored", machine.getMachineName());
            return List.of();
        }

        List<ReadingResult> results = new ArrayList<>();

        for (CompetitionMachine cm : associations) {
            var competition = competitionRepository.findById(cm.getPassword()).orElse(null);
            if (competition == null || !competition.isEnabled()) {
                continue;
            }

            Integer bib = req.bib();
            Integer cc = req.cc();

            var reading = new MachineReading();
            reading.setPassword(cm.getPassword());
            reading.setMachine(machine);
            reading.setBib(bib);
            reading.setCc(cc != null ? String.valueOf(cc) : null);
            reading.setReadAt(LocalDateTime.now());

            if (!cm.isApproved()) {
                reading.setFound(false);
                machineReadingRepository.save(reading);
                log.info("Machine reading buffered (unapproved machine={}): bib={}, cc={}",
                        machine.getMachineName(), bib, cc);
                results.add(new ReadingResult(bib != null ? bib : 0, "", "", "", false));
                continue;
            }

            String competitionId = competition.getCompetitionId();
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
                if (!dnsService.isStarted(cm.getPassword(), runner.bibNumber())) {
                    dnsService.markStarted(cm.getPassword(), runner.bibNumber(), machine.getMachineName());
                }
                log.info("Machine reading: {} (bib={}, machine={})",
                        runner.name(), runner.bibNumber(), machine.getMachineName());
                LocalTime startTime = runner.startTime();
                results.add(new ReadingResult(
                        runner.bibNumber(),
                        startTime != null ? startTime.toString() : "",
                        runner.name(),
                        runner.className(),
                        true));
            } else {
                log.info("Machine reading: runner not found (bib={}, cc={}, machine={})",
                        bib, cc, machine.getMachineName());
                results.add(new ReadingResult(bib != null ? bib : 0, "", "", "", false));
            }
        }

        return results;
    }
}
