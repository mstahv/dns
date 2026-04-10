package com.dns.raspireader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Controls the Raspberry Pi onboard ACT LED via sysfs.
 * Works without Pi4J — just needs write access to /sys/class/leds/ACT/.
 * The trigger is set to "none" for manual control, and restored on shutdown.
 */
public class OnboardLed {

    private static final Logger LOG = Logger.getLogger(OnboardLed.class.getName());
    private static final String[] LED_NAMES = {"ACT", "ACTLED", "led0", "led1"};
    private static final long BLINK_INTERVAL_MS = 200;
    private static final long BLINK_DURATION_MS = 1000;

    private final Path ledTrigger;
    private final Path ledBrightness;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "onboard-led");
        t.setDaemon(true);
        return t;
    });

    private String originalTrigger;
    private ScheduledFuture<?> blinkTask;
    private ScheduledFuture<?> stopTask;
    private boolean ledState;

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

    public synchronized void blink() {
        stopBlinking();
        blinkTask = scheduler.scheduleAtFixedRate(() -> {
            ledState = !ledState;
            write(ledState);
        }, 0, BLINK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void extendBlinking() {
        if (stopTask != null && !stopTask.isDone()) {
            stopTask.cancel(false);
            stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void stopBlinking() {
        if (blinkTask != null) {
            blinkTask.cancel(false);
            blinkTask = null;
        }
        if (stopTask != null) {
            stopTask.cancel(false);
            stopTask = null;
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
