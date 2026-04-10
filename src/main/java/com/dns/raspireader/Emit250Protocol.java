package com.dns.raspireader;

/**
 * Handles parsing of Emit 250 serial protocol messages.
 * Protocol: 217-byte messages, all bytes XOR'd with 0xDF.
 * Card number is 3 bytes little-endian at offset 2-4 (after XOR decode).
 */
public class Emit250Protocol {

    static final int MESSAGE_LENGTH = 217;
    static final byte XOR_KEY = (byte) 0xDF;
    private static final int BUFFER_SIZE = MESSAGE_LENGTH * 3;

    private final byte[] ringBuffer = new byte[BUFFER_SIZE];
    private int writePos = 0;
    private int availableBytes = 0;

    public record CardReading(int cardNumber, int productionWeek, int productionYear) {}

    /**
     * Feed raw bytes from serial port into the protocol parser.
     * Returns a CardReading if a valid message was found, null otherwise.
     */
    public CardReading feedBytes(byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            ringBuffer[writePos] = (byte) (data[offset + i] ^ XOR_KEY);
            writePos = (writePos + 1) % BUFFER_SIZE;
            availableBytes = Math.min(availableBytes + 1, BUFFER_SIZE);
        }
        return tryParse();
    }

    private CardReading tryParse() {
        if (availableBytes < MESSAGE_LENGTH) {
            return null;
        }

        // Search for preamble 0xFF 0xFF in decoded data
        int searchStart = (writePos - availableBytes + BUFFER_SIZE) % BUFFER_SIZE;

        for (int i = 0; i <= availableBytes - MESSAGE_LENGTH; i++) {
            int pos = (searchStart + i) % BUFFER_SIZE;
            int nextPos = (pos + 1) % BUFFER_SIZE;

            if ((ringBuffer[pos] & 0xFF) == 0xFF && (ringBuffer[nextPos] & 0xFF) == 0xFF) {
                // Found preamble, try to extract message
                byte[] message = new byte[MESSAGE_LENGTH];
                for (int j = 0; j < MESSAGE_LENGTH; j++) {
                    message[j] = ringBuffer[(pos + j) % BUFFER_SIZE];
                }

                if (validateChecksums(message)) {
                    // Consume bytes up to end of this message
                    int consumed = i + MESSAGE_LENGTH;
                    availableBytes -= consumed;

                    int cardNumber = (message[2] & 0xFF)
                            | ((message[3] & 0xFF) << 8)
                            | ((message[4] & 0xFF) << 16);
                    int week = message[6] & 0xFF;
                    int year = message[7] & 0xFF;

                    return new CardReading(cardNumber, week, year);
                }
            }
        }
        return null;
    }

    private boolean validateChecksums(byte[] message) {
        // Head checksum: sum of bytes 2-9 mod 256 == 0
        int headSum = 0;
        for (int i = 2; i <= 9; i++) {
            headSum += message[i] & 0xFF;
        }
        if ((headSum & 0xFF) != 0) {
            return false;
        }

        // Transfer checksum: sum of all 217 bytes mod 256 == 0
        int totalSum = 0;
        for (int i = 0; i < MESSAGE_LENGTH; i++) {
            totalSum += message[i] & 0xFF;
        }
        return (totalSum & 0xFF) == 0;
    }
}
