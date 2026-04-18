package com.example.dns.service;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.example.dns.api.MachineReadingWebSocketHandler;
import com.example.dns.domain.Machine;
import jakarta.annotation.PostConstruct;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;

/**
 * Captures server-side log entries from machine-related classes
 * into an in-memory ring buffer. Allows retrieving logs filtered
 * by machine name/ID for display in the UI.
 */
@Component
public class ServerLogBuffer extends AppenderBase<ILoggingEvent> {

    private static final int MAX_ENTRIES = 2000;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final Deque<String> buffer = new ArrayDeque<>();

    @PostConstruct
    void attachToLoggers() {
        var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        setContext(loggerContext);
        start();
        loggerContext.getLogger(MachineReadingWebSocketHandler.class).addAppender(this);
        loggerContext.getLogger(MachineReadingService.class).addAppender(this);
        loggerContext.getLogger(DnsService.class).addAppender(this);
    }

    @Override
    protected synchronized void append(ILoggingEvent event) {
        String timestamp = FMT.format(Instant.ofEpochMilli(event.getTimeStamp()));
        String line = timestamp + " " + event.getLevel() + " " + event.getFormattedMessage();
        buffer.addLast(line);
        while (buffer.size() > MAX_ENTRIES) {
            buffer.removeFirst();
        }
    }

    /**
     * Returns log lines that mention the given machine's name or ID.
     */
    public synchronized String getLogsForMachine(Machine machine) {
        String name = machine.getMachineName();
        String id = machine.getMachineId();
        return buffer.stream()
                .filter(line -> line.contains(name) || line.contains(id))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Returns all captured log lines (unfiltered).
     */
    public synchronized String getAllLogs() {
        return String.join("\n", buffer);
    }
}
