package com.dns.raspireader;

import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controls a passive piezo buzzer (e.g. from Arduino Starter Kit R4) via GPIO.
 * Generates tones by toggling the pin at an audible frequency.
 * <p>
 * Signals:
 * <ul>
 *   <li>Three short beeps — runner is late (start time already passed)</li>
 *   <li>One long beep — error (card not found, server error)</li>
 * </ul>
 */
public class BuzzerController {

    static final int BUZZER_GPIO_PIN = 22;
    private static final int TONE_FREQUENCY_HZ = 2000;
    private static final int SHORT_BEEP_MS = 150;
    private static final int SHORT_BEEP_GAP_MS = 100;
    private static final int LONG_BEEP_MS = 800;

    private final DigitalOutput buzzer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "buzzer-controller");
        t.setDaemon(true);
        return t;
    });

    public BuzzerController(Context pi4j) {
        this.buzzer = pi4j.digitalOutput().create(BUZZER_GPIO_PIN);
    }

    /** Three short beeps — runner is late */
    public void threeShortBeeps() {
        executor.execute(() -> {
            tone(SHORT_BEEP_MS);
            pause(SHORT_BEEP_GAP_MS);
            tone(SHORT_BEEP_MS);
            pause(SHORT_BEEP_GAP_MS);
            tone(SHORT_BEEP_MS);
        });
    }

    /** One long beep — error condition (card not found, server error) */
    public void oneLongBeep() {
        executor.execute(() -> tone(LONG_BEEP_MS));
    }

    private void tone(int durationMs) {
        long halfPeriodNs = 500_000_000L / TONE_FREQUENCY_HZ;
        long endNs = System.nanoTime() + (long) durationMs * 1_000_000L;
        while (System.nanoTime() < endNs) {
            buzzer.high();
            busyWaitNs(halfPeriodNs);
            buzzer.low();
            busyWaitNs(halfPeriodNs);
        }
    }

    private void busyWaitNs(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    private void pause(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        buzzer.low();
        executor.shutdownNow();
    }
}
