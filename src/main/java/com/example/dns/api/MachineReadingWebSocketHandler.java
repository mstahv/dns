package com.example.dns.api;

import com.example.dns.domain.CompetitionMachine;
import com.example.dns.domain.CompetitionMachineRepository;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.Machine;
import com.example.dns.service.MachineReadingService;
import com.example.dns.service.MachineReadingService.ReadingRequest;
import com.example.dns.service.MachineReadingService.ReadingResult;
import com.example.dns.service.StartListLookupService.ControlCardEntry;
import com.example.dns.service.TulospalveluService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * WebSocket endpoint for machine readings. Minimizes latency by keeping
 * the connection open: authenticate once, then stream readings.
 *
 * Protocol:
 * 1. Client sends auth: {"type":"auth","machineId":"emit-reader-1"}
 *    Server responds:    {"type":"auth","ok":true}
 *    Server sends:       {"type":"startlist","data":{"cc_num":{"bib":1,"st":"12:30"},...}}
 *
 * 2. Client sends readings: {"bib":123}  or  {"cc":456}
 *    Server responds:        [{"bib":...,"startTime":"...","name":"...","className":"...","found":true}]
 *
 * 3. Server may push updated startlist at any time (e.g. machine associated
 *    to new competition, or start list refreshed from tulospalvelu.fi).
 */
@Component
public class MachineReadingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MachineReadingWebSocketHandler.class);

    private final MachineReadingService machineReadingService;
    private final TulospalveluService tulospalveluService;
    private final CompetitionRepository competitionRepository;
    private final CompetitionMachineRepository competitionMachineRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Machine> sessionMachines = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessionById = new ConcurrentHashMap<>();

    public MachineReadingWebSocketHandler(MachineReadingService machineReadingService,
                                          TulospalveluService tulospalveluService,
                                          CompetitionRepository competitionRepository,
                                          CompetitionMachineRepository competitionMachineRepository) {
        this.machineReadingService = machineReadingService;
        this.tulospalveluService = tulospalveluService;
        this.competitionRepository = competitionRepository;
        this.competitionMachineRepository = competitionMachineRepository;
    }

    @PostConstruct
    void registerStartListListener() {
        tulospalveluService.addStartListUpdateListener(this::onStartListUpdated);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var node = objectMapper.readTree(message.getPayload());

        // Auth message
        if (node.has("type") && "auth".equals(node.get("type").asText())) {
            String machineId = node.get("machineId").asText();
            Machine machine = machineReadingService.resolveOrCreateMachine(machineId);
            sessionMachines.put(session.getId(), machine);
            sessionById.put(session.getId(), session);
            log.info("WebSocket machine authenticated: {} (session={})", machineId, session.getId());

            String authResponse = objectMapper.writeValueAsString(Map.of("type", "auth", "ok", true));
            session.sendMessage(new TextMessage(authResponse));

            sendStartListData(session, machine);
            return;
        }

        // Reading message — must be authenticated
        Machine machine = sessionMachines.get(session.getId());
        if (machine == null) {
            String error = objectMapper.writeValueAsString(
                    Map.of("type", "error", "error", "Ei autentikoitu. Lähetä ensin: {\"type\":\"auth\",\"machineId\":\"...\"}"));
            session.sendMessage(new TextMessage(error));
            return;
        }

        Integer bib = node.has("bib") && !node.get("bib").isNull() ? node.get("bib").asInt() : null;
        Integer cc = node.has("cc") && !node.get("cc").isNull() ? node.get("cc").asInt() : null;

        List<ReadingResult> results = machineReadingService.processReading(machine, new ReadingRequest(bib, cc));

        String response = objectMapper.writeValueAsString(results);
        session.sendMessage(new TextMessage(response));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Machine machine = sessionMachines.remove(session.getId());
        sessionById.remove(session.getId());
        if (machine != null) {
            log.info("WebSocket machine disconnected: {} (session={})", machine.getMachineId(), session.getId());
        }
    }

    /**
     * Called from UI when a machine's competition associations change
     * (added, removed, or approval toggled). Pushes updated start list
     * data to the connected machine if online.
     */
    public void notifyMachineUpdated(Machine machine) {
        pushToMachine(machine);
    }

    /**
     * Called when tulospalvelu.fi start list is refreshed with new createTime.
     * Finds all connected machines associated with that competition and pushes
     * updated data.
     */
    private void onStartListUpdated(String competitionId) {
        // Find which machines are connected and associated with this competition
        Set<Long> affectedMachineIds = competitionRepository.findByCompetitionId(competitionId).stream()
                .flatMap(c -> competitionMachineRepository.findByPassword(c.getPassword()).stream())
                .filter(CompetitionMachine::isApproved)
                .map(cm -> cm.getMachine().getId())
                .collect(Collectors.toSet());

        for (var entry : sessionMachines.entrySet()) {
            if (affectedMachineIds.contains(entry.getValue().getId())) {
                WebSocketSession session = sessionById.get(entry.getKey());
                if (session != null && session.isOpen()) {
                    try {
                        sendStartListData(session, entry.getValue());
                        log.info("Pushed updated start list to machine {} after IOF XML refresh (competitionId={})",
                                entry.getValue().getMachineId(), competitionId);
                    } catch (IOException e) {
                        log.warn("Failed to push start list to machine {}",
                                entry.getValue().getMachineId(), e);
                    }
                }
            }
        }
    }

    private void pushToMachine(Machine machine) {
        for (var entry : sessionMachines.entrySet()) {
            if (entry.getValue().getId().equals(machine.getId())) {
                WebSocketSession session = sessionById.get(entry.getKey());
                if (session != null && session.isOpen()) {
                    try {
                        sendStartListData(session, machine);
                        log.info("Pushed updated start list to machine {} (session={})",
                                machine.getMachineId(), session.getId());
                    } catch (IOException e) {
                        log.warn("Failed to push start list to machine {}", machine.getMachineId(), e);
                    }
                }
            }
        }
    }

    /**
     * Returns true if the given machine has an active WebSocket connection.
     */
    public boolean isOnline(Machine machine) {
        return sessionMachines.values().stream()
                .anyMatch(m -> m.getId().equals(machine.getId()));
    }

    private void sendStartListData(WebSocketSession session, Machine machine) throws IOException {
        Map<String, ControlCardEntry> data = machineReadingService.buildStartListDataForMachine(machine);
        String json = objectMapper.writeValueAsString(Map.of("type", "startlist", "data", data));
        session.sendMessage(new TextMessage(json));
    }
}
