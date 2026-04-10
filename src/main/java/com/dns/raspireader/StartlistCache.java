package com.dns.raspireader;

import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local cache of the startlist received from the server via WebSocket.
 * Maps emit card number to bib and start time for immediate LED feedback.
 */
public class StartlistCache {

    private static final Logger LOG = Logger.getLogger(StartlistCache.class.getName());

    public record Entry(int bib, LocalTime startTime) {}

    private static final Pattern ENTRY_PATTERN =
            Pattern.compile("\"(\\d+)\"\\s*:\\s*\\{\\s*\"bib\"\\s*:\\s*(\\d+)\\s*,\\s*\"st\"\\s*:\\s*\"([^\"]*)\"");

    private final Map<Integer, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Parse and replace the entire cache from a startlist JSON message.
     * Expected format: {"type":"startlist","data":{"12345":{"bib":1,"st":"12:00:00"},...}}
     */
    public void update(String json) {
        Map<Integer, Entry> newEntries = new ConcurrentHashMap<>();
        Matcher matcher = ENTRY_PATTERN.matcher(json);
        while (matcher.find()) {
            try {
                int cc = Integer.parseInt(matcher.group(1));
                int bib = Integer.parseInt(matcher.group(2));
                String timeStr = matcher.group(3);
                LocalTime st = timeStr.isEmpty() ? null : LocalTime.parse(timeStr);
                newEntries.put(cc, new Entry(bib, st));
            } catch (Exception e) {
                LOG.warning("Failed to parse startlist entry: " + e.getMessage());
            }
        }
        entries.clear();
        entries.putAll(newEntries);
        LOG.info("Startlist updated: " + entries.size() + " entries");
    }

    /**
     * Look up a card number in the cache.
     * @return the entry or null if not found
     */
    public Entry lookup(int cardNumber) {
        return entries.get(cardNumber);
    }

    public int size() {
        return entries.size();
    }
}
