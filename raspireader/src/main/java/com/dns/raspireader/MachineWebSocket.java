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
                            Runnable updateCallback) {
        this.wsUrl = wsUrl;
        this.machineId = machineId;
        this.version = version;
        this.startlistCache = startlistCache;
        this.responseCallback = responseCallback;
        this.updateCallback = updateCallback;
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
        } else if (message.contains("\"type\":\"error\"")) {
            LOG.warning("Server error: " + message);
        } else {
            LOG.fine("Unknown message: " + message);
        }
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
