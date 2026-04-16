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

    static final int PWM_CHIP = 0;
    static final int PWM_CHANNEL = 0;

    private static final double CLOSED_ANGLE = 0;
    private static final double OPEN_ANGLE = 90;
    private static final long OPEN_DURATION_MS = 2000;

    private final Sg90Servo servo;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gate-controller");
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> closeTask;
    private volatile boolean isOpen = false;

    public GateController() throws IOException {
        this.servo = new Sg90Servo(new PwmChip(PWM_CHIP, PWM_CHANNEL));
        servo.init();
        servo.setAngle(CLOSED_ANGLE);
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
