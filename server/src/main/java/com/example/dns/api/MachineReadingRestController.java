package com.example.dns.api;

import com.example.dns.domain.Machine;
import com.example.dns.service.MachineReadingService;
import com.example.dns.service.MachineReadingService.ReadingRequest;
import com.example.dns.service.MachineReadingService.ReadingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

@RestController
@RequestMapping("/api")
public class MachineReadingRestController {

    private static final Logger log = LoggerFactory.getLogger(MachineReadingRestController.class);

    private final MachineReadingService machineReadingService;
    private final MachineReadingWebSocketHandler webSocketHandler;

    public MachineReadingRestController(MachineReadingService machineReadingService,
                                        MachineReadingWebSocketHandler webSocketHandler) {
        this.machineReadingService = machineReadingService;
        this.webSocketHandler = webSocketHandler;
    }

    @PostMapping("/machine-reading")
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

    @PostMapping("/machine-logs/{machineId}")
    public ResponseEntity<Void> uploadLogs(@PathVariable String machineId,
                                           @RequestBody byte[] body) {
        try {
            String logContent;
            try (var gzis = new GZIPInputStream(new ByteArrayInputStream(body));
                 Reader reader = new InputStreamReader(gzis, StandardCharsets.UTF_8)) {
                var sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) >= 0) sb.append(buf, 0, n);
                logContent = sb.toString();
            }
            log.info("Received log upload from machine {} ({} bytes gzip, {} chars)",
                    machineId, body.length, logContent.length());

            Machine machine = machineReadingService.resolveOrCreateMachine(machineId);
            webSocketHandler.completeLogRequest(machine, logContent);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Failed to process log upload from machine {}", machineId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
