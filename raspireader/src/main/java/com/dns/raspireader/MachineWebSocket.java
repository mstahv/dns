package com.dns.raspireader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalTime;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket client for the machine reading protocol.
 * Handles connection, authentication, startlist updates, and sending readings.
 */
public class MachineWebSocket {

    private static final Logger LOG = Logger.getLogger(MachineWebSocket.class.getName());

    public record ServerResponse(boolean found, LocalTime startTime) {}

    private static final int LOG_MSG_MAX_LEN = 100;
    private static final Pattern FOUND_PATTERN = Pattern.compile("\"found\"\\s*:\\s*(true|false)");
    private static final Pattern START_TIME_PATTERN = Pattern.compile("\"startTime\"\\s*:\\s*\"([^\"]*)\"");

    private static String truncate(String msg) {
        return msg.length() <= LOG_MSG_MAX_LEN ? msg : msg.substring(0, LOG_MSG_MAX_LEN) + "...(" + msg.length() + " chars)";
    }

    private final String wsUrl;
    private final String machineId;
    private final String version;
    private final StartlistCache startlistCache;
    private final Consumer<ServerResponse> responseCallback;
    private final Runnable updateCallback;
    private final Runnable shutdownCallback;
    private final Supplier<String> logsCallback;
    private final Supplier<String> fullLogsCallback;
    private final HttpClient httpClient;
    private final ScheduledExecutorService reconnectScheduler;

    private static final long PING_INTERVAL_SEC = 15;
    private static final long PONG_TIMEOUT_SEC = 10;

    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile long lastPongTime;
    private int reconnectDelaySec = 1;
    private java.util.concurrent.ScheduledFuture<?> pingTask;

    public MachineWebSocket(String wsUrl, String machineId, String version,
                            StartlistCache startlistCache,
                            Consumer<ServerResponse> responseCallback,
                            Runnable updateCallback,
                            Runnable shutdownCallback,
                            Supplier<String> logsCallback,
                            Supplier<String> fullLogsCallback) {
        this.wsUrl = wsUrl;
        this.machineId = machineId;
        this.version = version;
        this.startlistCache = startlistCache;
        this.responseCallback = responseCallback;
        this.updateCallback = updateCallback;
        this.shutdownCallback = shutdownCallback;
        this.logsCallback = logsCallback;
        this.fullLogsCallback = fullLogsCallback;
        this.httpClient = HttpClient.newHttpClient();
        this.reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    public void connect() {
        try {
            LOG.info("Connecting to " + wsUrl);
            httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new Listener())
                    .thenAccept(ws -> {
                        webSocket = ws;
                        connected = true;
                        lastPongTime = System.currentTimeMillis();
                        reconnectDelaySec = 1;
                        String versionField = version != null ? ",\"version\":\"" + version + "\"" : "";
                        String authMsg = "{\"type\":\"auth\",\"machineId\":\"" + machineId + "\"" + versionField + "}";
                        LOG.info("WS connected, sending: " + truncate(authMsg));
                        ws.sendText(authMsg, true);
                        startPingLoop();
                    })
                    .exceptionally(e -> {
                        LOG.warning("WebSocket connection failed: " + e.getMessage());
                        scheduleReconnect();
                        return null;
                    });
        } catch (Exception e) {
            LOG.warning("Failed to initiate WebSocket connection: " + e.getMessage());
            scheduleReconnect();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Send a card reading to the server.
     * @return true if sent, false if not connected
     */
    public boolean sendReading(int cardNumber) {
        WebSocket ws = webSocket;
        if (ws != null && connected) {
            try {
                String msg = "{\"cc\":" + cardNumber + "}";
                LOG.info("WS sending: " + truncate(msg));
                ws.sendText(msg, true)
                        .exceptionally(e -> {
                            LOG.warning("Send failed for card " + cardNumber + ": " + e.getMessage());
                            handleDisconnect();
                            return null;
                        });
                return true;
            } catch (Exception e) {
                LOG.warning("Failed to send reading: " + e.getMessage());
                handleDisconnect();
                return false;
            }
        }
        return false;
    }

    private void startPingLoop() {
        stopPingLoop();
        pingTask = reconnectScheduler.scheduleAtFixedRate(() -> {
            WebSocket ws = webSocket;
            if (ws != null && connected) {
                long sinceLastPong = System.currentTimeMillis() - lastPongTime;
                if (sinceLastPong > (PING_INTERVAL_SEC + PONG_TIMEOUT_SEC) * 1000) {
                    LOG.warning("No pong received in " + sinceLastPong + "ms, reconnecting...");
                    handleDisconnect();
                    return;
                }
                ws.sendPing(java.nio.ByteBuffer.allocate(0))
                        .exceptionally(e -> {
                            LOG.warning("Ping failed: " + e.getMessage());
                            handleDisconnect();
                            return null;
                        });
            }
        }, PING_INTERVAL_SEC, PING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void stopPingLoop() {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
    }

    private void handleDisconnect() {
        if (!connected) return;
        connected = false;
        stopPingLoop();
        WebSocket ws = webSocket;
        if (ws != null) {
            try { ws.abort(); } catch (Exception ignored) {}
            webSocket = null;
        }
        scheduleReconnect();
    }

    public void shutdown() {
        stopPingLoop();
        reconnectScheduler.shutdownNow();
        WebSocket ws = webSocket;
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"); } catch (Exception ignored) {}
        }
    }

    private void scheduleReconnect() {
        connected = false;
        LOG.info("Reconnecting in " + reconnectDelaySec + "s...");
        reconnectScheduler.schedule(this::connect, reconnectDelaySec, TimeUnit.SECONDS);
        reconnectDelaySec = Math.min(reconnectDelaySec * 2, 30);
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder messageBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);
            if (last) {
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
            lastPongTime = System.currentTimeMillis();
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info("WebSocket closed: " + statusCode + " " + reason);
            handleDisconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.log(Level.WARNING, "WebSocket error", error);
            handleDisconnect();
        }
    }

    private void handleMessage(String message) {
        LOG.info("WS received: " + truncate(message));
        if (message.contains("\"type\":\"startlist\"")) {
            startlistCache.update(message);
        } else if (message.contains("\"type\":\"auth\"")) {
            if (message.contains("\"ok\":true")) {
                LOG.info("Authenticated successfully");
            } else {
                LOG.warning("Authentication failed: " + message);
            }
        } else if (message.startsWith("[")) {
            parseReadingResponse(message);
        } else if (message.contains("\"type\":\"requestUpdate\"")) {
            LOG.info("OTA update requested by server");
            if (updateCallback != null) {
                updateCallback.run();
            }
        } else if (message.contains("\"type\":\"requestShutdown\"")) {
            LOG.info("Shutdown requested by server");
            if (shutdownCallback != null) {
                shutdownCallback.run();
            }
        } else if (message.contains("\"type\":\"requestLogs\"")) {
            LOG.info("Log request received from server");
            if (logsCallback != null) {
                // Run in separate thread to avoid blocking WebSocket listener
                // (journalctl can be slow on Pi Zero, blocking would kill ping/pong)
                Thread.ofVirtual().name("log-collector").start(() -> {
                    try {
                        String logContent = logsCallback.get();
                        String escaped = escapeJson(logContent);
                        String msg = "{\"type\":\"logs\",\"data\":\"" + escaped + "\"}";
                        LOG.info("Sending logs response: " + msg.length() + " chars");
                        WebSocket ws = webSocket;
                        if (ws != null && connected) {
                            ws.sendText(msg, true)
                                    .thenRun(() -> LOG.info("Logs response sent successfully"))
                                    .exceptionally(e2 -> {
                                        LOG.warning("Logs send failed: " + e2.getMessage());
                                        return null;
                                    });
                        } else {
                            LOG.warning("Cannot send logs: ws=" + (ws != null) + " connected=" + connected);
                        }
                    } catch (Exception e) {
                        LOG.warning("Failed to collect/send logs: " + e.getMessage());
                    }
                });
            }
        } else if (message.contains("\"type\":\"requestFullLogs\"")) {
            LOG.info("Full log request received from server");
            if (fullLogsCallback != null) {
                Thread.ofVirtual().name("full-log-collector").start(() -> {
                    try {
                        String logContent = fullLogsCallback.get();
                        String escaped = escapeJson(logContent);
                        String msg = "{\"type\":\"fullLogs\",\"data\":\"" + escaped + "\"}";
                        LOG.info("Sending full logs response: " + msg.length() + " chars");
                        sendResponse(msg);
                    } catch (Exception e) {
                        LOG.warning("Failed to collect/send full logs: " + e.getMessage());
                    }
                });
            }
        } else if (message.contains("\"type\":\"requestWifiList\"")) {
            handleWifiListRequest();
        } else if (message.contains("\"type\":\"addWifi\"")) {
            handleAddWifiRequest(message);
        } else if (message.contains("\"type\":\"error\"")) {
            LOG.warning("Server error: " + message);
        } else {
            LOG.fine("Unknown message: " + message);
        }
    }

    private void handleWifiListRequest() {
        Thread.ofVirtual().name("wifi-list").start(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("nmcli", "-t", "-f", "NAME,TYPE,ACTIVE", "connection", "show");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes()).trim();
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

                // Filter to wifi connections, mark active one
                // Format: NAME:802-11-wireless:yes/no
                StringBuilder wifiNames = new StringBuilder();
                for (String line : output.split("\n")) {
                    if (line.contains("wireless")) {
                        if (!wifiNames.isEmpty()) wifiNames.append("\n");
                        boolean active = line.endsWith(":yes");
                        // Remove type and active fields, keep name
                        String withoutActive = active
                                ? line.substring(0, line.lastIndexOf(":"))
                                : line.substring(0, line.lastIndexOf(":"));
                        String name = withoutActive.substring(0, withoutActive.lastIndexOf(":"));
                        wifiNames.append(name);
                        if (active) wifiNames.append(" [aktiivinen]");
                    }
                }

                String escaped = escapeJson(wifiNames.toString());
                sendResponse("{\"type\":\"wifiList\",\"data\":\"" + escaped + "\"}");
            } catch (Exception e) {
                LOG.warning("Failed to list WiFi networks: " + e.getMessage());
                sendResponse("{\"type\":\"wifiList\",\"data\":\"Virhe: " + escapeJson(e.getMessage()) + "\"}");
            }
        });
    }

    private void handleAddWifiRequest(String message) {
        Thread.ofVirtual().name("wifi-add").start(() -> {
            try {
                java.util.regex.Matcher ssidMatcher = java.util.regex.Pattern.compile("\"ssid\"\\s*:\\s*\"([^\"]*)\"").matcher(message);
                java.util.regex.Matcher pskMatcher = java.util.regex.Pattern.compile("\"password\"\\s*:\\s*\"([^\"]*)\"").matcher(message);

                if (!ssidMatcher.find()) {
                    sendResponse("{\"type\":\"wifiAdded\",\"ok\":false,\"message\":\"SSID puuttuu\"}");
                    return;
                }
                String ssid = ssidMatcher.group(1);
                String psk = pskMatcher.find() ? pskMatcher.group(1) : "";

                ProcessBuilder pb;
                if (psk.isEmpty()) {
                    pb = new ProcessBuilder("nmcli", "connection", "add",
                            "type", "wifi", "con-name", ssid, "ssid", ssid);
                } else {
                    pb = new ProcessBuilder("nmcli", "connection", "add",
                            "type", "wifi", "con-name", ssid, "ssid", ssid,
                            "wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", psk);
                }
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String output = new String(process.getInputStream().readAllBytes()).trim();
                int exitCode = process.waitFor();

                boolean ok = exitCode == 0;
                sendResponse("{\"type\":\"wifiAdded\",\"ok\":" + ok + ",\"message\":\"" + escapeJson(output) + "\"}");
                LOG.info("WiFi add result (exit=" + exitCode + "): " + output);
            } catch (Exception e) {
                LOG.warning("Failed to add WiFi network: " + e.getMessage());
                sendResponse("{\"type\":\"wifiAdded\",\"ok\":false,\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        });
    }

    private void sendResponse(String text) {
        WebSocket ws = webSocket;
        if (ws != null && connected) {
            ws.sendText(text, true).exceptionally(e -> {
                LOG.warning("Failed to send response: " + e.getMessage());
                return null;
            });
        }
    }

    static String escapeJson(String s) {
        var sb = new StringBuilder(s.length() + 64);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private void parseReadingResponse(String message) {
        Matcher foundMatcher = FOUND_PATTERN.matcher(message);
        boolean found = foundMatcher.find() && "true".equals(foundMatcher.group(1));

        LocalTime startTime = null;
        Matcher timeMatcher = START_TIME_PATTERN.matcher(message);
        if (timeMatcher.find()) {
            String timeStr = timeMatcher.group(1).trim();
            if (!timeStr.isEmpty()) {
                try {
                    startTime = LocalTime.parse(timeStr);
                } catch (Exception e) {
                    LOG.warning("Failed to parse startTime: " + timeStr);
                }
            }
        }

        LOG.info("Server response: found=" + found + ", startTime=" + startTime);
        responseCallback.accept(new ServerResponse(found, startTime));
    }
}
