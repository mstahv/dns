package com.dns.raspireader;

import com.fazecast.jSerialComm.SerialPort;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.zip.GZIPOutputStream;
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
        int pwmChip = -1;
        int pwmChannel = -1;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> baseUrl = args[++i];
                case "--machine-id" -> machineId = args[++i];
                case "--serial" -> serialDevice = args[++i];
                case "--log" -> logPath = args[++i];
                case "--emitcheck" -> emitCheck = true;
                case "--pwm-chip" -> pwmChip = Integer.parseInt(args[++i]);
                case "--pwm-channel" -> pwmChannel = Integer.parseInt(args[++i]);
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

        // Probe serial port once: missing = console input mode (dev fallback).
        // Otherwise we discard this handle and let the reconnect loop reopen it.
        boolean consoleMode = findSerialPort(serialDevice) == null;
        if (consoleMode) {
            LOG.info("  Serial port: not found — console input mode");
        }

        // Initialize Pi4J for GPIO
        Context pi4j = null;
        LedController led = null;
        try {
            pi4j = Pi4J.newAutoContext();
            led = new LedController(pi4j);
            LOG.info("  GPIO: green LED=BCM" + LedController.GREEN_GPIO_PIN
                    + ", red LED=BCM" + LedController.RED_GPIO_PIN);
        } catch (Exception e) {
            LOG.warning("GPIO not available (not running on Pi?): " + e.getMessage());
            LOG.warning("  LED signaling will be disabled");
        }

        // Initialize buzzer (same Pi4J context as LEDs)
        BuzzerController buzzer = null;
        if (pi4j != null) {
            try {
                buzzer = new BuzzerController(pi4j);
                LOG.info("  Buzzer: BCM" + BuzzerController.BUZZER_GPIO_PIN);
            } catch (Exception e) {
                LOG.warning("Buzzer not available: " + e.getMessage());
            }
        }

        // Initialize servo gate (hardware PWM via sysfs, independent of Pi4J)
        GateController gate = null;
        try {
            // Auto-detect PWM chip/channel based on Pi model if not specified
            if (pwmChip < 0 || pwmChannel < 0) {
                int[] defaults = GateController.detectPwmDefaults();
                if (pwmChip < 0) pwmChip = defaults[0];
                if (pwmChannel < 0) pwmChannel = defaults[1];
            }
            gate = new GateController(pwmChip, pwmChannel);
            LOG.info("  Servo gate: pwmchip" + gate.getPwmChip()
                    + "/pwm" + gate.getPwmChannel() + " (GPIO 18)");
        } catch (Exception e) {
            LOG.warning("Servo gate not available: " + e.getMessage());
            LOG.warning("  Ensure dtoverlay=pwm is set in /boot/firmware/config.txt");
            LOG.warning("  Try --pwm-chip N --pwm-channel N if auto-detect is wrong");
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
        final BuzzerController buzzerRef = buzzer;
        final GateController gateRef = gate;
        final boolean emitCheckMode = emitCheck;

        final String finalBaseUrl = baseUrl;
        final String finalMachineId = machineId;

        // Derive WebSocket URL from base URL
        String wsUrl = baseUrl.replaceFirst("^http", "ws") + "/ws/machine-reading";

        // Server response callback — may override LED/buzzer/gate if it disagrees with cache
        MachineWebSocket ws = new MachineWebSocket(wsUrl, machineId, version, startlistCache,
                response -> {
                    int displayedCard = lastDisplayedCard;
                    if (displayedCard < 0 || ledRef == null) return;

                    // Look up what the cache said for this card
                    var cached = startlistCache.lookup(displayedCard);
                    boolean cacheFoundIt = cached != null;

                    // If server disagrees with cache, override feedback
                    if (response.found() && !cacheFoundIt) {
                        // Cache missed but server found it — show found pattern
                        showFoundFeedback(ledRef, buzzerRef, gateRef, response.startTime());
                    } else if (!response.found() && cacheFoundIt) {
                        // Cache had it but server says not found — correct to not-registered
                        ledRef.greenOnBlinkRed();
                        if (buzzerRef != null) buzzerRef.oneLongBeep();
                    } else if (!response.found() && !cacheFoundIt) {
                        // Both agree: not found
                        ledRef.greenOnBlinkRed();
                    }
                    // If both agree found, server response may have more accurate startTime
                    if (response.found() && cacheFoundIt && response.startTime() != null) {
                        showFoundFeedback(ledRef, buzzerRef, gateRef, response.startTime());
                    }
                },
                () -> triggerOtaUpdate(),
                RaspiReader::triggerShutdown,
                () -> uploadLogs(finalBaseUrl, finalMachineId));

        ws.connect();

        // Tracks whether the USB serial reader is currently open. In console mode
        // there is no reader to monitor, so we treat it as always "connected".
        final java.util.concurrent.atomic.AtomicBoolean readerConnected =
                new java.util.concurrent.atomic.AtomicBoolean(consoleMode);

        // Start onboard LED connection status indicator
        if (onboardLed != null) {
            onboardLed.startStatusIndicator(() -> ws.isConnected() && readerConnected.get());
        }

        // Start idle heartbeat on external LEDs — green "OK" heartbeat requires
        // both server and reader to be alive, otherwise we fall through to the
        // disconnected (red) indicator.
        if (led != null) {
            led.startIdleHeartbeat(() -> ws.isConnected() && readerConnected.get());
        }

        // Register shutdown hook
        final Context pi4jRef = pi4j;
        final LedController ledShutdownRef = led;
        final BuzzerController buzzerShutdownRef = buzzer;
        final GateController gateShutdownRef = gate;
        final OnboardLed onboardLedShutdownRef = onboardLed;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down...");
            ws.shutdown();
            if (ledShutdownRef != null) ledShutdownRef.shutdown();
            if (buzzerShutdownRef != null) buzzerShutdownRef.shutdown();
            if (gateShutdownRef != null) gateShutdownRef.shutdown();
            if (onboardLedShutdownRef != null) onboardLedShutdownRef.shutdown();
            if (pi4jRef != null) pi4jRef.shutdown();
        }));

        // Console fallback: no serial reader present, accept manual input from stdin
        if (consoleMode) {
            System.out.println("Emit-lukijaa ei löytynyt. Syötä emit-korttien numeroita:");
            var consoleInput = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            try {
                String line;
                while ((line = consoleInput.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int cardNumber;
                    try {
                        cardNumber = Integer.parseInt(line);
                    } catch (NumberFormatException e) {
                        System.out.println("Virheellinen numero: " + line);
                        continue;
                    }
                    processCardRead(cardNumber, ledRef, buzzerRef, gateRef,
                            startlistCache, readLogger, ws, buffer, emitCheckMode);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Console input error", e);
            }
            return;
        }

        // Serial mode: outer loop reconnects on USB unplug, inner loop reads cards
        byte[] readBuffer = new byte[256];
        int lastCardNumber = -1;
        int previousCardNumber = -1;
        long lastReadTime = 0;
        final long REREAD_TIMEOUT_MS = 10_000;
        final int MAX_CONSECUTIVE_ERRORS = 5;
        final String serialDeviceFinal = serialDevice;

        while (true) {
            // (Re)connect serial port
            SerialPort port = findSerialPort(serialDeviceFinal);
            if (port == null) {
                LOG.warning("No serial port found, retrying in 10s...");
                try { Thread.sleep(10_000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
                continue;
            }
            port.setComPortParameters(BAUD_RATE, DATA_BITS, STOP_BITS, PARITY);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0);
            if (!port.openPort()) {
                LOG.warning("Failed to open " + port.getSystemPortName() + ", retrying in 10s...");
                try { Thread.sleep(10_000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); break;
                }
                continue;
            }
            LOG.info("Serial port opened: " + port.getSystemPortName());
            readerConnected.set(true);

            // Read loop — breaks out on repeated errors to reconnect
            int consecutiveErrors = 0;
            while (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                try {
                    int bytesRead = port.readBytes(readBuffer, readBuffer.length);
                    // bytesRead == 0  → timeout, no data (normal)
                    // bytesRead  < 0  → read error, e.g. USB unplugged: fd is gone and
                    //                   the call returns -1 immediately (no blocking),
                    //                   so we must count it as an error to break out
                    //                   and let the outer loop rescan device nodes.
                    if (bytesRead < 0 || !port.isOpen()) {
                        consecutiveErrors++;
                        LOG.warning("Serial read failed (" + consecutiveErrors + "/"
                                + MAX_CONSECUTIVE_ERRORS + "), errno="
                                + port.getLastErrorCode());
                        try { Thread.sleep(500); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt(); return;
                        }
                        continue;
                    }
                    if (bytesRead == 0) {
                        continue;
                    }
                    consecutiveErrors = 0;

                    Emit250Protocol.CardReading reading = protocol.feedBytes(readBuffer, 0, bytesRead);
                    if (reading == null) continue;

                    int cardNumber = reading.cardNumber();
                    long now = System.currentTimeMillis();

                    // Same card still on reader — extend feedback, skip sending
                    if (cardNumber == lastCardNumber) {
                        boolean differentCardInBetween = previousCardNumber != lastCardNumber && previousCardNumber != -1;
                        boolean timeoutExceeded = (now - lastReadTime) > REREAD_TIMEOUT_MS;
                        if (!differentCardInBetween && !timeoutExceeded) {
                            if (ledRef != null) ledRef.extendBlinking();
                            if (gateRef != null) gateRef.extendOpen();
                            continue;
                        }
                    }
                    previousCardNumber = lastCardNumber;
                    lastCardNumber = cardNumber;
                    lastReadTime = now;

                    processCardRead(cardNumber, ledRef, buzzerRef, gateRef,
                            startlistCache, readLogger, ws, buffer, emitCheckMode);
                } catch (Exception e) {
                    consecutiveErrors++;
                    LOG.log(Level.WARNING, "Serial port error (" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + ")", e);
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); return;
                    }
                }
            }

            // Too many errors — close port, will reconnect at top of outer loop
            LOG.warning("Serial port lost, closing and reconnecting...");
            readerConnected.set(false);
            try { port.closePort(); } catch (Exception ignored) {}
            try { Thread.sleep(5000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
    }

    private static void processCardRead(int cardNumber,
                                        LedController ledRef, BuzzerController buzzerRef,
                                        GateController gateRef, StartlistCache startlistCache,
                                        ReadLogger readLogger, MachineWebSocket ws,
                                        CardBuffer buffer, boolean emitCheckMode) {
        lastDisplayedCard = cardNumber;
        LOG.info("Card read: " + cardNumber);
        readLogger.logRead(cardNumber);
        if (ledRef != null) ledRef.recordActivity();

        // TESTING: open the gate on every card read regardless of result
        if (gateRef != null) gateRef.open();

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
            return;
        }

        // Normal mode: immediate feedback from cache, then send to server
        if (ledRef != null) {
            var cached = startlistCache.lookup(cardNumber);
            if (cached != null) {
                showFoundFeedback(ledRef, buzzerRef, gateRef, cached.startTime());
            } else {
                ledRef.greenOnBlinkRed();
                if (buzzerRef != null) buzzerRef.oneLongBeep();
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

    static void showFoundFeedback(LedController led, BuzzerController buzzer,
                                   GateController gate, LocalTime startTime) {
        if (startTime != null) {
            long secondsToStart = Duration.between(LocalTime.now(), startTime).toSeconds();
            if (secondsToStart < 0) {
                // Already late — let through but warn
                if (led != null) led.blinkGreenFast();
                if (buzzer != null) buzzer.threeShortBeeps();
                if (gate != null) gate.open();
            } else if (secondsToStart <= 4 * 60) {
                // 0–4 min: guide runner to correct start
                if (led != null) led.blinkGreen();
                if (gate != null) gate.open();
            } else if (secondsToStart < 5 * 60) {
                // 4:01–4:59: normal pre-start, steady green
                if (led != null) led.greenSteady();
                if (gate != null) gate.open();
            } else {
                // >5 min: too early — no gate, no buzzer
                if (led != null) led.alternateGreenRed();
            }
        } else {
            // Found but no start time — OK
            if (led != null) led.blinkGreen();
            if (gate != null) gate.open();
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
                    "journalctl", "-u", "raspireader", "--since", "today", "--no-pager")
                    .redirectErrorStream(true)
                    .start();
            // Read output first — if we wait before reading, the pipe buffer
            // fills up and journalctl blocks, causing a spurious timeout
            sb.append(new String(process.getInputStream().readAllBytes()));
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
        } catch (Exception e) {
            sb.append("journalctl error: ").append(e.getMessage()).append("\n");
        }
        try {
            var readLog = Path.of("/var/log/raspireader/reads.log");
            if (java.nio.file.Files.exists(readLog)) {
                var lines = java.nio.file.Files.readAllLines(readLog);
                sb.append("\n--- reads.log (").append(lines.size()).append(" lines) ---\n");
                for (String line : lines) {
                    sb.append(line).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("reads.log error: ").append(e.getMessage()).append("\n");
        }
        LOG.info("Collected logs: " + sb.length() + " chars");
        return sb.toString();
    }

    private static void uploadLogs(String baseUrl, String machineId) {
        try {
            String logs = collectLogs();
            var baos = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(baos)) {
                gzip.write(logs.getBytes(StandardCharsets.UTF_8));
            }
            byte[] compressed = baos.toByteArray();
            LOG.info("Uploading logs: " + logs.length() + " chars, " + compressed.length + " bytes gzipped");

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/machine-logs/" + machineId))
                    .header("Content-Encoding", "gzip")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(compressed))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            var response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            LOG.info("Log upload response: " + response.statusCode());
        } catch (Exception e) {
            LOG.warning("Failed to upload logs: " + e.getMessage());
        }
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
                  --pwm-chip <n>       PWM chip number for servo (default: auto-detect by Pi model)
                  --pwm-channel <n>    PWM channel for servo (default: auto-detect by Pi model)
                                       Pi 5: chip=0 channel=2, older Pis: chip=0 channel=0
                  --help               Show this help
                """);
    }
}
