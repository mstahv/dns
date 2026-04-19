package com.dns.raspireader;

import com.fazecast.jSerialComm.SerialPort;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main application: reads Emit 250 cards via serial port, signals via LED,
 * caches startlist locally for instant feedback, and communicates via WebSocket.
 */
public class RaspiReader {

    private static final Logger LOG = Logger.getLogger(RaspiReader.class.getName());

    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = SerialPort.TWO_STOP_BITS;
    private static final int PARITY = SerialPort.NO_PARITY;

    // Track last displayed card so server response callback can override LEDs
    private static volatile int lastDisplayedCard = -1;

    public static void main(String[] args) {
        String baseUrl = "https://dns.virit.in";
        String machineId = getMachineId();
        String serialDevice = "";
        String logPath = "/var/log/raspireader/reads.log";
        boolean emitCheck = false;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> baseUrl = args[++i];
                case "--machine-id" -> machineId = args[++i];
                case "--serial" -> serialDevice = args[++i];
                case "--log" -> logPath = args[++i];
                case "--emitcheck" -> emitCheck = true;
                case "--help" -> {
                    printHelp();
                    return;
                }
            }
        }

        String version = getVersion();
        LOG.info("DNS RaspiReader starting...");
        if (emitCheck) LOG.info("  Mode: EMITCHECK (read-only, no start registration)");
        LOG.info("  Version: " + (version != null ? version : "unknown"));
        LOG.info("  Server URL: " + baseUrl);
        LOG.info("  Machine ID: " + machineId);
        LOG.info("  Log file: " + logPath);

        // Find serial port
        SerialPort port = findSerialPort(serialDevice);
        if (port == null) {
            LOG.severe("No Emit 250 serial port found. Use --serial to specify device path.");
            System.exit(1);
        }
        LOG.info("  Serial port: " + port.getSystemPortName());

        // Initialize Pi4J for GPIO
        Context pi4j = null;
        LedController led = null;
        try {
            pi4j = Pi4J.newAutoContext();
            led = new LedController(pi4j);
            LOG.info("  GPIO LEDs: green=BCM" + LedController.GREEN_GPIO_PIN
                    + ", red=BCM" + LedController.RED_GPIO_PIN);
        } catch (Exception e) {
            LOG.warning("GPIO not available (not running on Pi?): " + e.getMessage());
            LOG.warning("  LED signaling will be disabled");
        }

        // Initialize onboard ACT LED
        OnboardLed onboardLed = null;
        try {
            onboardLed = new OnboardLed();
        } catch (Exception e) {
            LOG.warning("Onboard ACT LED not available: " + e.getMessage());
        }

        StartlistCache startlistCache = new StartlistCache();
        CardBuffer buffer = new CardBuffer();
        ReadLogger readLogger = new ReadLogger(Path.of(logPath));
        Emit250Protocol protocol = new Emit250Protocol();

        final LedController ledRef = led;
        final boolean emitCheckMode = emitCheck;

        // Derive WebSocket URL from base URL
        String wsUrl = baseUrl.replaceFirst("^http", "ws") + "/ws/machine-reading";

        // Server response callback — may override LED if it disagrees with cache
        MachineWebSocket ws = new MachineWebSocket(wsUrl, machineId, version, startlistCache,
                response -> {
                    int displayedCard = lastDisplayedCard;
                    if (displayedCard < 0 || ledRef == null) return;

                    // Look up what the cache said for this card
                    var cached = startlistCache.lookup(displayedCard);
                    boolean cacheFoundIt = cached != null;

                    // If server disagrees with cache, override LEDs
                    if (response.found() && !cacheFoundIt) {
                        // Cache missed but server found it — show found pattern
                        showFoundLed(ledRef, response.startTime());
                    } else if (!response.found() && cacheFoundIt) {
                        // Cache had it but server says not found (emit changed?) — correct to not-registered
                        ledRef.greenOnBlinkRed();
                    } else if (!response.found() && !cacheFoundIt) {
                        // Both agree: not found
                        ledRef.greenOnBlinkRed();
                    }
                    // If both agree found, server response may have more accurate startTime
                    if (response.found() && cacheFoundIt && response.startTime() != null) {
                        showFoundLed(ledRef, response.startTime());
                    }
                },
                () -> triggerOtaUpdate(),
                RaspiReader::triggerShutdown,
                RaspiReader::collectLogs);

        ws.connect();

        // Start onboard LED connection status indicator
        if (onboardLed != null) {
            onboardLed.startStatusIndicator(ws::isConnected);
        }

        // Start idle heartbeat on external LEDs
        if (led != null) {
            led.startIdleHeartbeat(ws::isConnected);
        }

        // Configure and open serial port
        port.setComPortParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);

        if (!port.openPort()) {
            LOG.severe("Failed to open serial port: " + port.getSystemPortName());
            System.exit(1);
        }
        LOG.info("Serial port opened. Waiting for Emit cards...");

        // Register shutdown hook
        final SerialPort portRef = port;
        final Context pi4jRef = pi4j;
        final LedController ledShutdownRef = led;
        final OnboardLed onboardLedShutdownRef = onboardLed;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down...");
            ws.shutdown();
            portRef.closePort();
            if (ledShutdownRef != null) ledShutdownRef.shutdown();
            if (onboardLedShutdownRef != null) onboardLedShutdownRef.shutdown();
            if (pi4jRef != null) pi4jRef.shutdown();
        }));

        // Main read loop
        byte[] readBuffer = new byte[256];
        int lastCardNumber = -1;
        int previousCardNumber = -1;
        long lastReadTime = 0;
        final long REREAD_TIMEOUT_MS = 10_000;

        while (true) {
            try {
                int bytesRead = port.readBytes(readBuffer, readBuffer.length);
                if (bytesRead <= 0) {
                    continue;
                }

                Emit250Protocol.CardReading reading = protocol.feedBytes(readBuffer, 0, bytesRead);
                if (reading != null) {
                    int cardNumber = reading.cardNumber();
                    long now = System.currentTimeMillis();

                    // Same card still on reader — extend LED blinking, skip sending
                    // unless a different card was read in between or timeout exceeded
                    if (cardNumber == lastCardNumber) {
                        boolean differentCardInBetween = previousCardNumber != lastCardNumber && previousCardNumber != -1;
                        boolean timeoutExceeded = (now - lastReadTime) > REREAD_TIMEOUT_MS;
                        if (!differentCardInBetween && !timeoutExceeded) {
                            if (ledRef != null) ledRef.extendBlinking();
                            continue;
                        }
                    }
                    previousCardNumber = lastCardNumber;
                    lastCardNumber = cardNumber;
                    lastReadTime = now;
                    lastDisplayedCard = cardNumber;

                    LOG.info("Card read: " + cardNumber);
                    readLogger.logRead(cardNumber);
                    if (ledRef != null) ledRef.recordActivity();

                    if (emitCheckMode) {
                        // Emitcheck: read-only, just check if card is in startlist
                        if (ledRef != null) {
                            var cached = startlistCache.lookup(cardNumber);
                            if (cached != null) {
                                ledRef.blinkGreen();
                            } else {
                                ledRef.blinkRed();
                            }
                        }
                    } else {
                        // Normal mode: immediate LED feedback from cache, then send to server
                        if (ledRef != null) {
                            var cached = startlistCache.lookup(cardNumber);
                            if (cached != null) {
                                showFoundLed(ledRef, cached.startTime());
                            } else {
                                ledRef.greenOnBlinkRed();
                            }
                        }

                        // Send via WebSocket (async) — server response may override LED
                        if (!ws.sendReading(cardNumber)) {
                            buffer.add(cardNumber);
                            LOG.info("Buffered card (WS disconnected): " + cardNumber);
                        }

                        // Flush buffer if connected
                        if (ws.isConnected() && buffer.hasData()) {
                            for (int buffered : buffer.snapshot()) {
                                if (ws.sendReading(buffered)) {
                                    buffer.removeAll(java.util.List.of(buffered));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error reading serial port", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    static void showFoundLed(LedController led, LocalTime startTime) {
        if (startTime != null) {
            long secondsToStart = Duration.between(LocalTime.now(), startTime).toSeconds();
            if (secondsToStart < 0) {
                // Already late
                led.blinkGreenFast();
            } else if (secondsToStart <= 4 * 60) {
                // 0–4 min: guide runner to correct start
                led.blinkGreen();
            } else if (secondsToStart < 5 * 60) {
                // 4:01–4:59: normal pre-start, steady green
                led.greenSteady();
            } else {
                // >5 min: too early
                led.alternateGreenRed();
            }
        } else {
            led.blinkGreen();
        }
    }

    private static SerialPort findSerialPort(String devicePath) {
        if (!devicePath.isEmpty()) {
            SerialPort port = SerialPort.getCommPort(devicePath);
            return port;
        }

        // Auto-detect: look for FTDI device (Emit 250 uses FTDI chip)
        for (SerialPort port : SerialPort.getCommPorts()) {
            String desc = port.getPortDescription().toLowerCase();
            String name = port.getSystemPortName().toLowerCase();
            if (desc.contains("ftdi") || desc.contains("ft232")
                    || desc.contains("emit") || name.contains("ttyusb")) {
                return port;
            }
        }

        // Fallback: try first ttyUSB port
        for (SerialPort port : SerialPort.getCommPorts()) {
            if (port.getSystemPortName().toLowerCase().contains("ttyusb")) {
                return port;
            }
        }

        return null;
    }

    private static String getMachineId() {
        String hostname = getHostname();
        // Try to read machine ID from /etc/machine-id
        try {
            return hostname + "-" + java.nio.file.Files.readString(Path.of("/etc/machine-id")).trim();
        } catch (Exception e) {
            // Fallback: hostname + MAC address
            try {
                var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    var ni = interfaces.nextElement();
                    byte[] mac = ni.getHardwareAddress();
                    if (mac != null && mac.length == 6 && !ni.isLoopback()) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : mac) {
                            sb.append(String.format("%02x", b));
                        }
                        return hostname + "-" + sb;
                    }
                }
            } catch (Exception ex) {
                // ignore
            }
            return hostname;
        }
    }

    private static String getVersion() {
        // Read git commit hash and date from the repo clone
        // Format: "abc1234 2026-04-12 18:30"
        var repoDir = new java.io.File("/opt/raspireader/repo");
        try {
            String hash = runGit(repoDir, "git", "rev-parse", "--short", "HEAD");
            if (hash == null) return null;
            String date = runGit(repoDir, "git", "log", "-1", "--format=%ci");
            if (date != null && date.length() >= 16) {
                // "%ci" gives "2026-04-12 18:30:00 +0300", take date+time
                date = date.substring(0, 16);
            }
            return date != null ? hash + " " + date : hash;
        } catch (Exception e) {
            return null;
        }
    }

    private static String runGit(java.io.File dir, String... command) {
        try {
            var process = new ProcessBuilder(command)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 && !output.isEmpty() ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String collectLogs() {
        var sb = new StringBuilder();
        try {
            var process = new ProcessBuilder(
                    "journalctl", "-u", "raspireader", "-n", "50", "--no-pager")
                    .redirectErrorStream(true)
                    .start();
            // Read with timeout — don't let journalctl hang
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            sb.append(new String(process.getInputStream().readAllBytes()));
            if (!finished) {
                process.destroyForcibly();
                sb.append("\n(journalctl timeout)\n");
            }
        } catch (Exception e) {
            sb.append("journalctl error: ").append(e.getMessage()).append("\n");
        }
        try {
            var readLog = Path.of("/var/log/raspireader/reads.log");
            if (java.nio.file.Files.exists(readLog)) {
                var lines = java.nio.file.Files.readAllLines(readLog);
                int start = Math.max(0, lines.size() - 30);
                sb.append("\n--- reads.log (last ").append(lines.size() - start).append(" lines) ---\n");
                for (int i = start; i < lines.size(); i++) {
                    sb.append(lines.get(i)).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("reads.log error: ").append(e.getMessage()).append("\n");
        }
        String result = sb.toString();
        if (result.length() > 16_000) {
            result = result.substring(result.length() - 16_000);
        }
        LOG.info("Collected logs: " + result.length() + " chars");
        return result;
    }

    private static void triggerShutdown() {
        LOG.info("Shutdown requested, executing shutdown -h now...");
        try {
            new ProcessBuilder("shutdown", "-h", "now").start();
        } catch (Exception e) {
            LOG.severe("Failed to shutdown: " + e.getMessage());
        }
    }

    private static void triggerOtaUpdate() {
        LOG.info("Triggering OTA update...");
        try {
            // Write a trigger file — systemd path unit (raspireader-update.path)
            // watches for this file and starts raspireader-update.service,
            // which is completely independent of this process.
            java.nio.file.Files.writeString(
                    Path.of("/opt/raspireader/update-requested"),
                    java.time.Instant.now().toString());
            LOG.info("Update trigger file written, systemd path unit will start update");
        } catch (Exception e) {
            LOG.severe("Failed to write update trigger: " + e.getMessage());
        }
    }

    private static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void printHelp() {
        System.out.println("""
                DNS RaspiReader - Emit 250 Card Reader for Raspberry Pi

                Usage: java -jar raspireader.jar [options]

                Options:
                  --url <url>          Server URL (default: https://dns.virit.in)
                                       WebSocket connects to ws://<host>:<port>/ws/machine-reading
                  --machine-id <id>    Machine identifier (default: auto-detect from MAC/machine-id)
                  --serial <device>    Serial port device (default: auto-detect /dev/ttyUSB*)
                  --log <path>         Log file path (default: /var/log/raspireader/reads.log)
                  --emitcheck          Emit check mode: read-only, no start registration.
                                       Green blink = card found, red blink = not found.
                  --help               Show this help
                """);
    }
}
