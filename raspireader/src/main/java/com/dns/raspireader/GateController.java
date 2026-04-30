package com.dns.raspireader;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls a start gate using an SG90 micro servo via hardware PWM (sysfs).
 * The gate opens when the runner is cleared to start (found + within time window or late)
 * and auto-closes 2 seconds after the last reader message.
 * <p>
 * Uses GPIO 18 (physical pin 12) which is hardware PWM0.
 * Requires {@code dtoverlay=pwm} in /boot/firmware/config.txt.
 */
public class GateController {

    private static final Logger LOG = Logger.getLogger(GateController.class.getName());

    private static final double CLOSED_ANGLE = 0;
    private static final double OPEN_ANGLE = 90;
    private static final long OPEN_DURATION_MS = 2000;

    private final Sg90Servo servo;
    private final int pwmChip;
    private final int pwmChannel;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gate-controller");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> closeTask;
    private volatile boolean isOpen = false;

    public GateController(int pwmChip, int pwmChannel) throws IOException {
        this.pwmChip = pwmChip;
        this.pwmChannel = pwmChannel;
        this.servo = new Sg90Servo(new PwmChip(pwmChip, pwmChannel));
        servo.init();
        servo.setAngle(CLOSED_ANGLE);
    }

    public int getPwmChip() {
        return pwmChip;
    }

    public int getPwmChannel() {
        return pwmChannel;
    }

    /**
     * Detects the correct PWM chip and channel for GPIO 18 based on Pi model.
     * Pi 5 (RP1): pwmchip0/pwm2, older Pis: pwmchip0/pwm0.
     */
    static int[] detectPwmDefaults() {
        try {
            String model = java.nio.file.Files.readString(
                    java.nio.file.Path.of("/proc/device-tree/model")).trim();
            if (model.contains("Pi 5")) {
                return new int[]{0, 2}; // RP1: GPIO 18 = PWM0_CH2
            }
        } catch (Exception e) {
            // ignore
        }
        return new int[]{0, 0}; // BCM2710/BCM2711: GPIO 18 = PWM0_CH0
    }

    /** Opens the gate. Schedules auto-close after 2 seconds. */
    public synchronized void open() {
        try {
            if (!isOpen) {
                servo.setAngle(OPEN_ANGLE);
                isOpen = true;
            }
            resetCloseTimer();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to open gate", e);
        }
    }

    /** Extends the open time by resetting the 2-second timer. */
    public synchronized void extendOpen() {
        if (isOpen) {
            resetCloseTimer();
        }
    }

    private void resetCloseTimer() {
        if (closeTask != null) {
            closeTask.cancel(false);
        }
        closeTask = scheduler.schedule(this::doClose, OPEN_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void doClose() {
        try {
            servo.setAngle(CLOSED_ANGLE);
            isOpen = false;
            closeTask = null;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to close gate", e);
        }
    }

    public void shutdown() {
        if (closeTask != null) closeTask.cancel(false);
        doClose();
        scheduler.shutdownNow();
        try {
            servo.shutdown();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to shutdown servo", e);
        }
    }
}
