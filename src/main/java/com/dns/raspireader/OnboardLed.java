package com.dns.raspireader;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Controls the Raspberry Pi onboard ACT LED via sysfs.
 * Works without Pi4J — just needs write access to /sys/class/leds/ACT/.
 * The trigger is set to "none" for manual control, and restored on shutdown.
 *
 * Connection status indicator:
 * - WebSocket connected: double flash every 5 seconds
 * - No network: continuous blinking
 */
public class OnboardLed {

    private static final Logger LOG = Logger.getLogger(OnboardLed.class.getName());
    private static final String[] LED_NAMES = {"ACT", "ACTLED", "led0", "led1"};
    private static final long STATUS_CHECK_INTERVAL_MS = 5000;
    private static final long FLASH_ON_MS = 80;
    private static final long FLASH_GAP_MS = 120;
    private static final long CONTINUOUS_BLINK_MS = 300;

    private final Path ledTrigger;
    private final Path ledBrightness;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "onboard-led");
        t.setDaemon(true);
        return t;
    });

    private String originalTrigger;
    private ScheduledFuture<?> statusTask;
    private ScheduledFuture<?> blinkTask;
    private boolean ledState;
    private BooleanSupplier wsConnectedCheck;

    public OnboardLed() throws IOException {
        Path found = findLed();
        if (found == null) {
            throw new IOException("No onboard LED found in /sys/class/leds/ "
                    + "(tried: " + String.join(", ", LED_NAMES) + ")");
        }
        this.ledTrigger = found.resolve("trigger");
        this.ledBrightness = found.resolve("brightness");

        // Save original trigger (e.g. "mmc0") and switch to manual control
        String triggerContent = Files.readString(ledTrigger);
        // Active trigger is wrapped in [brackets]
        int start = triggerContent.indexOf('[');
        int end = triggerContent.indexOf(']');
        originalTrigger = (start >= 0 && end > start)
                ? triggerContent.substring(start + 1, end)
                : "mmc0";
        Files.writeString(ledTrigger, "none");
        write(false);
        LOG.info("Onboard LED " + found.getFileName() + " set to manual control (was: " + originalTrigger + ")");
    }

    private static Path findLed() {
        for (String name : LED_NAMES) {
            Path path = Path.of("/sys/class/leds", name);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    /**
     * Start monitoring connection status and indicating it via the onboard LED.
     * @param wsConnected supplier that returns true when WebSocket is connected
     */
    public void startStatusIndicator(BooleanSupplier wsConnected) {
        this.wsConnectedCheck = wsConnected;
        statusTask = scheduler.scheduleAtFixedRate(this::updateStatus,
                0, STATUS_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void updateStatus() {
        stopBlinking();
        if (wsConnectedCheck != null && wsConnectedCheck.getAsBoolean()) {
            // Connected: double flash
            doubleFlash();
        } else if (!hasNetwork()) {
            // No network: continuous blink
            blinkTask = scheduler.scheduleAtFixedRate(() -> {
                ledState = !ledState;
                write(ledState);
            }, 0, CONTINUOUS_BLINK_MS, TimeUnit.MILLISECONDS);
        } else {
            // Network OK but WS disconnected: single flash
            singleFlash();
        }
    }

    private void doubleFlash() {
        scheduler.schedule(() -> write(true), 0, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> write(false), FLASH_ON_MS, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> write(true), FLASH_ON_MS + FLASH_GAP_MS, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> write(false), FLASH_ON_MS * 2 + FLASH_GAP_MS, TimeUnit.MILLISECONDS);
    }

    private void singleFlash() {
        scheduler.schedule(() -> write(true), 0, TimeUnit.MILLISECONDS);
        scheduler.schedule(() -> write(false), FLASH_ON_MS, TimeUnit.MILLISECONDS);
    }

    private static boolean hasNetwork() {
        try {
            return InetAddress.getByName("1.1.1.1").isReachable(2000);
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized void stopBlinking() {
        if (blinkTask != null) {
            blinkTask.cancel(false);
            blinkTask = null;
        }
        write(false);
    }

    private void write(boolean on) {
        try {
            Files.writeString(ledBrightness, on ? "1" : "0");
        } catch (IOException e) {
            LOG.warning("Failed to write onboard LED: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (statusTask != null) statusTask.cancel(false);
        stopBlinking();
        scheduler.shutdownNow();
        // Restore original trigger
        try {
            Files.writeString(ledTrigger, originalTrigger);
            LOG.info("Onboard ACT LED restored to trigger: " + originalTrigger);
        } catch (IOException e) {
            LOG.warning("Failed to restore onboard LED trigger: " + e.getMessage());
        }
    }
}
