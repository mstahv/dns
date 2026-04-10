package com.example.dns.api;

import com.example.dns.domain.Machine;
import com.example.dns.service.MachineReadingService;
import com.example.dns.service.MachineReadingService.ReadingRequest;
import com.example.dns.service.MachineReadingService.ReadingResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/machine-reading")
public class MachineReadingRestController {

    private final MachineReadingService machineReadingService;

    public MachineReadingRestController(MachineReadingService machineReadingService) {
        this.machineReadingService = machineReadingService;
    }

    @PostMapping
    public ResponseEntity<List<ReadingResult>> handleReadings(
            @RequestHeader("X-Machine-Id") String machineId,
            @RequestBody List<ReadingRequest> readings) {

        Machine machine = machineReadingService.resolveOrCreateMachine(machineId);

        List<ReadingResult> results = new ArrayList<>();
        for (ReadingRequest req : readings) {
            results.addAll(machineReadingService.processReading(machine, req));
        }

        return ResponseEntity.ok(results);
    }
}
