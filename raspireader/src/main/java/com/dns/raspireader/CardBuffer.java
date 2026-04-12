package com.dns.raspireader;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Thread-safe buffer of unique card numbers waiting to be sent to the server.
 */
public class CardBuffer {

    private final LinkedHashSet<Integer> pending = new LinkedHashSet<>();

    /**
     * Add a card number to the buffer.
     * @return true if this was a new card number (not already in buffer)
     */
    public synchronized boolean add(int cardNumber) {
        return pending.add(cardNumber);
    }

    /**
     * @return true if there are unsent card numbers
     */
    public synchronized boolean hasData() {
        return !pending.isEmpty();
    }

    /**
     * Get a snapshot of all pending card numbers.
     */
    public synchronized List<Integer> snapshot() {
        return new ArrayList<>(pending);
    }

    /**
     * Remove successfully sent card numbers from the buffer.
     */
    public synchronized void removeAll(List<Integer> sent) {
        pending.removeAll(sent);
    }
}
