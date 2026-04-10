package com.dns.raspireader;

import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Controls two LEDs: green (GPIO 17) and red (GPIO 27).
 * Green = runner found, Red = runner not found or error.
 * Blinks the active LED on/off for a configurable duration.
 */
public class LedController {

    static final int GREEN_GPIO_PIN = 17;
    static final int RED_GPIO_PIN = 27;
    private static final long BLINK_INTERVAL_MS = 200;
    private static final long BLINK_DURATION_MS = 1000;

    private final DigitalOutput greenLed;
    private final DigitalOutput redLed;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "led-controller");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> blinkTask;
    private ScheduledFuture<?> stopTask;

    public LedController(Context pi4j) {
        this.greenLed = pi4j.digitalOutput().create(GREEN_GPIO_PIN);
        this.redLed = pi4j.digitalOutput().create(RED_GPIO_PIN);
    }

    /** 0–4 min to start: green blinks normally — staff guides runner to correct start */
    public synchronized void blinkGreen() {
        startBlinking(greenLed, redLed);
    }

    /** 4–5 min to start: green stays on — normal pre-start situation */
    public synchronized void greenSteady() {
        stopBlinking();
        greenLed.high();
        stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /** >5 min to start: green and red alternate — runner is too early */
    public synchronized void alternateGreenRed() {
        stopBlinking();
        blinkTask = scheduler.scheduleAtFixedRate(() -> {
            synchronized (LedController.this) {
                greenLed.toggle();
                if (greenLed.isHigh()) {
                    redLed.low();
                } else {
                    redLed.high();
                }
            }
        }, 0, BLINK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /** Start time already passed: green blinks at double speed — runner is late */
    public synchronized void blinkGreenFast() {
        stopBlinking();
        redLed.low();
        blinkTask = scheduler.scheduleAtFixedRate(greenLed::toggle, 0, BLINK_INTERVAL_MS / 2, TimeUnit.MILLISECONDS);
        stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void blinkRed() {
        startBlinking(redLed, greenLed);
    }

    /**
     * Green LED stays on, red LED blinks.
     * Used when card was read OK but runner is not registered.
     */
    public synchronized void greenOnBlinkRed() {
        stopBlinking();
        greenLed.high();
        blinkTask = scheduler.scheduleAtFixedRate(redLed::toggle, 0, BLINK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Extend the current blinking by another full duration.
     * Call this when the same card is still on the reader.
     */
    public synchronized void extendBlinking() {
        if (stopTask != null && !stopTask.isDone()) {
            stopTask.cancel(false);
            stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void startBlinking(DigitalOutput active, DigitalOutput inactive) {
        stopBlinking();
        inactive.low();
        blinkTask = scheduler.scheduleAtFixedRate(active::toggle, 0, BLINK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        stopTask = scheduler.schedule(this::stopBlinking, BLINK_DURATION_MS, TimeUnit.MILLISECONDS);
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
        greenLed.low();
        redLed.low();
    }

    public void shutdown() {
        stopBlinking();
        scheduler.shutdownNow();
    }
}
