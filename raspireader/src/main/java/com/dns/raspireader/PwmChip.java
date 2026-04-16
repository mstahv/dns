package com.dns.raspireader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Low-level Java API for controlling hardware PWM channels by directly writing to sysfs files.
 * This class provides direct access to PWM chip functionality without using Pi4J, but with a
 * more fine-grained API suitable for controlling e.g. servo motors.
 *
 * Copied from https://github.com/mstahv/j-smoker/tree/main/pwmchip
 */
public class PwmChip {

    private final int chipNumber;
    private final int channel;
    private final String basePath;
    private boolean exported = false;

    public PwmChip(int chipNumber, int channel) {
        this.chipNumber = chipNumber;
        this.channel = channel;
        this.basePath = "/sys/class/pwm/pwmchip" + chipNumber;
    }

    public void export() throws IOException {
        if (exported) {
            return;
        }

        Path exportFile = Paths.get(basePath, "export");
        Files.writeString(exportFile, String.valueOf(channel));

        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        exported = true;
    }

    public void unexport() throws IOException {
        if (!exported) {
            return;
        }

        Path unexportFile = Paths.get(basePath, "unexport");
        Files.writeString(unexportFile, String.valueOf(channel));
        exported = false;
    }

    public void setPeriod(long periodNs) throws IOException {
        ensureExported();
        Path periodFile = Paths.get(basePath, "pwm" + channel, "period");
        Files.writeString(periodFile, String.valueOf(periodNs));
    }

    public void setPeriodMs(double periodMs) throws IOException {
        ensureExported();
        long periodNs = (long) (periodMs * 1_000_000);
        setPeriod(periodNs);
    }

    public void setDutyCycle(long dutyCycleNs) throws IOException {
        ensureExported();
        Path dutyCycleFile = Paths.get(basePath, "pwm" + channel, "duty_cycle");
        Files.writeString(dutyCycleFile, String.valueOf(dutyCycleNs));
    }

    public void setDutyCycleMs(double dutyCycleMs) throws IOException {
        ensureExported();
        long dutyCycleNs = (long) (dutyCycleMs * 1_000_000);
        setDutyCycle(dutyCycleNs);
    }

    public void enable() throws IOException {
        ensureExported();
        Path enableFile = Paths.get(basePath, "pwm" + channel, "enable");
        Files.writeString(enableFile, "1");
    }

    public void disable() throws IOException {
        ensureExported();
        Path enableFile = Paths.get(basePath, "pwm" + channel, "enable");
        Files.writeString(enableFile, "0");
    }

    public boolean isEnabled() throws IOException {
        ensureExported();
        Path enableFile = Paths.get(basePath, "pwm" + channel, "enable");
        String content = Files.readString(enableFile).trim();
        return "1".equals(content);
    }

    public long getPeriod() throws IOException {
        ensureExported();
        Path periodFile = Paths.get(basePath, "pwm" + channel, "period");
        String content = Files.readString(periodFile).trim();
        return Long.parseLong(content);
    }

    public double getPeriodMs() throws IOException {
        ensureExported();
        return getPeriod() / 1_000_000.0;
    }

    public long getDutyCycle() throws IOException {
        ensureExported();
        Path dutyCycleFile = Paths.get(basePath, "pwm" + channel, "duty_cycle");
        String content = Files.readString(dutyCycleFile).trim();
        return Long.parseLong(content);
    }

    public double getDutyCycleMs() throws IOException {
        ensureExported();
        return getDutyCycle() / 1_000_000.0;
    }

    public double getDutyCyclePercent() throws IOException {
        ensureExported();
        long period = getPeriod();
        long dutyCycle = getDutyCycle();
        return (dutyCycle * 100.0) / period;
    }

    public int getChipNumber() {
        return chipNumber;
    }

    public int getChannel() {
        return channel;
    }

    public boolean isExported() {
        return exported;
    }

    private void ensureExported() {
        if (!exported) {
            throw new IllegalStateException("PWM channel " + channel + " on chip " + chipNumber
                    + " is not exported. Call export() first.");
        }
    }

    public void close() throws IOException {
        unexport();
    }
}
