package com.dns.raspireader;

import com.fazecast.jSerialComm.SerialPort;
import com.pi4j.Pi4J;
import com.pi4j.context.Context;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main application: reads Emit 250 cards via serial port, signals via LED,
 * buffers unique card numbers, logs all reads, and pushes to REST endpoint.
 */
public class RaspiReader {

    private static final Logger LOG = Logger.getLogger(RaspiReader.class.getName());

    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = SerialPort.TWO_STOP_BITS;
    private static final int PARITY = SerialPort.NO_PARITY;


    public static void main(String[] args) {
        String baseUrl = "http://m4m.local:8080";
        String password = "";
        String machineId = getMachineId();
        String serialDevice = "";
        String logPath = "/var/log/raspireader/reads.log";

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> baseUrl = args[++i];
                case "--password" -> password = args[++i];
                case "--machine-id" -> machineId = args[++i];
                case "--serial" -> serialDevice = args[++i];
                case "--log" -> logPath = args[++i];
                case "--help" -> {
                    printHelp();
                    return;
                }
            }
        }

        LOG.info("DNS RaspiReader starting...");
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

        CardBuffer buffer = new CardBuffer();
        ReadLogger readLogger = new ReadLogger(Path.of(logPath));
        RestSender sender = new RestSender(baseUrl, password, machineId);
        Emit250Protocol protocol = new Emit250Protocol();

        final LedController ledRef = led;
        final OnboardLed onboardLedRef = onboardLed;

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
            portRef.closePort();
            if (ledShutdownRef != null) ledShutdownRef.shutdown();
            if (onboardLedShutdownRef != null) onboardLedShutdownRef.shutdown();
            if (pi4jRef != null) pi4jRef.shutdown();
        }));

        // Main read loop
        byte[] readBuffer = new byte[256];
        int lastCardNumber = -1;
        int previousCardNumber = -1; // card before the current one
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

                    // Same card still on reader — extend LED blinking, skip REST call
                    // unless a different card was read in between or timeout exceeded
                    if (cardNumber == lastCardNumber) {
                        boolean differentCardInBetween = previousCardNumber != lastCardNumber && previousCardNumber != -1;
                        boolean timeoutExceeded = (now - lastReadTime) > REREAD_TIMEOUT_MS;
                        if (!differentCardInBetween && !timeoutExceeded) {
                            if (ledRef != null) ledRef.extendBlinking();
                            if (onboardLedRef != null) onboardLedRef.extendBlinking();
                            continue;
                        }
                    }
                    previousCardNumber = lastCardNumber;
                    lastCardNumber = cardNumber;
                    lastReadTime = now;

                    LOG.info("Card read: " + cardNumber);
                    readLogger.logRead(cardNumber);

                    // Add to buffer (only unique numbers kept)
                    boolean isNew = buffer.add(cardNumber);
                    if (isNew) {
                        LOG.info("New card added to buffer: " + cardNumber);
                    } else {
                        LOG.info("Card already in buffer: " + cardNumber);
                    }

                    // Send immediately to REST endpoint
                    var toSend = buffer.snapshot();
                    var response = sender.send(toSend);
                    if (response.result() != RestSender.SendResult.FAILED) {
                        buffer.removeAll(toSend);
                    }

                    // Blink LEDs based on response
                    if (ledRef != null) {
                        switch (response.result()) {
                            case RUNNER_FOUND -> {
                                if (response.startTime() != null) {
                                    long minutesToStart = java.time.Duration.between(
                                            java.time.LocalTime.now(), response.startTime()).toMinutes();
                                    if (minutesToStart < 0) {
                                        ledRef.blinkGreenFast();
                                    } else if (minutesToStart < 4) {
                                        ledRef.blinkGreen();
                                    } else if (minutesToStart <= 5) {
                                        ledRef.greenSteady();
                                    } else {
                                        ledRef.alternateGreenRed();
                                    }
                                } else {
                                    ledRef.blinkGreen();
                                }
                            }
                            case CARD_NOT_REGISTERED -> ledRef.greenOnBlinkRed();
                            default -> ledRef.blinkRed();
                        }
                    }
                    if (onboardLedRef != null) {
                        onboardLedRef.blink();
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
        // Try to read machine ID from /etc/machine-id
        try {
            return java.nio.file.Files.readString(Path.of("/etc/machine-id")).trim();
        } catch (Exception e) {
            // Fallback: use MAC address or hostname
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
                        return sb.toString();
                    }
                }
            } catch (Exception ex) {
                // ignore
            }
            return "unknown";
        }
    }

    private static void printHelp() {
        System.out.println("""
                DNS RaspiReader - Emit 250 Card Reader for Raspberry Pi

                Usage: java -jar raspireader.jar [options]

                Options:
                  --url <url>          Server URL (default: http://m4m.local:8080)
                  --password <pwd>     Competition password (X-Competition-Password header)
                  --machine-id <id>    Machine identifier (default: auto-detect from MAC/machine-id)
                  --serial <device>    Serial port device (default: auto-detect /dev/ttyUSB*)
                  --log <path>         Log file path (default: /var/log/raspireader/reads.log)
                  --help               Show this help
                """);
    }
}
