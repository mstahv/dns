package com.dns.raspireader;

import java.io.IOException;

/**
 * Abstract base for PWM-controlled servo motors.
 * Subclasses define the servo's pulse width range, frequency, and angle limits.
 *
 * Copied from https://github.com/mstahv/j-smoker/tree/main/pwmchip
 */
public abstract class Servo {

    private final PwmChip pwmChip;

    protected Servo(PwmChip pwmChip) {
        this.pwmChip = pwmChip;
    }

    protected abstract int frequencyHz();

    protected abstract double minPulseMs();

    protected abstract double maxPulseMs();

    protected abstract double maxAngle();

    public void init() throws IOException {
        pwmChip.export();
        pwmChip.setPeriodMs(1000.0 / frequencyHz());
        setAngle(0);
        pwmChip.enable();
    }

    public void setAngle(double degrees) throws IOException {
        if (degrees < 0 || degrees > maxAngle()) {
            throw new IllegalArgumentException("Angle must be 0-" + (int) maxAngle() + "°, got: " + degrees);
        }
        double pulseMs = minPulseMs() + degrees / maxAngle() * (maxPulseMs() - minPulseMs());
        pwmChip.setDutyCycleMs(pulseMs);
    }

    public void shutdown() throws IOException {
        pwmChip.disable();
        pwmChip.unexport();
    }
}
