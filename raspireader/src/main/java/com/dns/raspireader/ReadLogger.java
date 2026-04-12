package com.dns.raspireader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs all card reads (including duplicates) with timestamps to a file.
 */
public class ReadLogger {

    private static final Logger LOG = Logger.getLogger(ReadLogger.class.getName());
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path logFile;

    public ReadLogger(Path logFile) {
        this.logFile = logFile;
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Cannot create log directory", e);
        }
    }

    public void logRead(int cardNumber) {
        String line = LocalDateTime.now().format(FORMAT) + " CARD=" + cardNumber + "\n";
        try {
            Files.writeString(logFile, line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to write to log file", e);
        }
    }
}
