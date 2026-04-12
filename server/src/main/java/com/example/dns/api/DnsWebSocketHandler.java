package com.example.dns.api;

import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.DnsService;
import com.example.dns.service.DnsService.StartedEvent;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint for real-time started runner events.
 * Clients subscribe with a competition password and receive all mark/unmark
 * events from any source (UI, REST API, machine reading).
 *
 * Protocol:
 * 1. Client sends auth: {"type":"auth","password":"secret123"}
 *    Server responds:    {"type":"auth","ok":true}
 *    Server sends:       {"type":"started","bibs":[1,2,3]}  (current state)
 *
 * 2. Server pushes events:
 *    {"type":"mark","bib":42,"registeredBy":"kellokalle"}
 *    {"type":"unmark","bib":42}
 */
@Component
public class DnsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DnsWebSocketHandler.class);

    private final DnsService dnsService;
    private final CompetitionRepository competitionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // sessionId → competition password
    private final Map<String, String> sessionPasswords = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessionById = new ConcurrentHashMap<>();

    public DnsWebSocketHandler(DnsService dnsService,
                               CompetitionRepository competitionRepository) {
        this.dnsService = dnsService;
        this.competitionRepository = competitionRepository;
    }

    @PostConstruct
    void registerListener() {
        dnsService.addStartedListener(this::onStartedEvent);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        var node = objectMapper.readTree(message.getPayload());

        if (!node.has("type") || !"auth".equals(node.get("type").asText())) {
            sendError(session, "Tuntematon viesti");
            return;
        }

        String password = node.get("password").asText();
        var competition = competitionRepository.findById(password);
        if (competition.isEmpty()) {
            String response = objectMapper.writeValueAsString(
                    Map.of("type", "auth", "ok", false, "error", "Väärä salasana"));
            session.sendMessage(new TextMessage(response));
            return;
        }

        sessionPasswords.put(session.getId(), password);
        sessionById.put(session.getId(), session);
        log.info("DNS WebSocket subscriber connected (session={}, competition={})",
                session.getId(), competition.get().getCompetitionId());

        // Auth OK
        String authResponse = objectMapper.writeValueAsString(Map.of("type", "auth", "ok", true));
        session.sendMessage(new TextMessage(authResponse));

        // Send current state
        Set<Integer> bibs = dnsService.getStartedBibs(password);
        String stateResponse = objectMapper.writeValueAsString(
                Map.of("type", "started", "bibs", bibs));
        session.sendMessage(new TextMessage(stateResponse));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionPasswords.remove(session.getId());
        sessionById.remove(session.getId());
    }

    private void onStartedEvent(StartedEvent event) {
        for (var entry : sessionPasswords.entrySet()) {
            if (entry.getValue().equals(event.password())) {
                WebSocketSession session = sessionById.get(entry.getKey());
                if (session != null && session.isOpen()) {
                    try {
                        Map<String, Object> msg;
                        if (event.started()) {
                            msg = Map.of("type", "mark", "bib", event.bibNumber(),
                                    "registeredBy", event.registeredBy() != null ? event.registeredBy() : "");
                        } else {
                            msg = Map.of("type", "unmark", "bib", event.bibNumber());
                        }
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
                    } catch (IOException e) {
                        log.warn("Failed to push started event to session {}", entry.getKey(), e);
                    }
                }
            }
        }
    }

    private void sendError(WebSocketSession session, String error) throws IOException {
        session.sendMessage(new TextMessage(
                objectMapper.writeValueAsString(Map.of("type", "error", "error", error))));
    }
}
