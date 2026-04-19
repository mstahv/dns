package com.example.dns.service;

import com.example.dns.domain.DnsEntry;
import com.example.dns.domain.DnsEntryRepository;
import com.vaadin.flow.signals.shared.SharedNumberSignal;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class DnsService {

    public record StartedEvent(String password, int bibNumber, String registeredBy, boolean started) {
    }

    private final DnsEntryRepository repository;
    private final Map<String, Set<Integer>> startedCache = new ConcurrentHashMap<>();
    private final Map<String, SharedNumberSignal> changeCounters = new ConcurrentHashMap<>();
    private final List<Consumer<StartedEvent>> startedListeners = new CopyOnWriteArrayList<>();

    public DnsService(DnsEntryRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns a shared signal that increments on every change.
     * UI views can subscribe to this to react to changes from other users.
     */
    public SharedNumberSignal getChangeSignal(String password) {
        return changeCounters.computeIfAbsent(password, k -> new SharedNumberSignal());
    }

    public Set<Integer> getStartedBibs(String password) {
        return startedCache.computeIfAbsent(password, this::loadFromDb);
    }

    public void markStarted(String password, int bibNumber, String registeredBy) {
        markStarted(password, bibNumber, registeredBy, null);
    }

    public void markStarted(String password, int bibNumber, String registeredBy, String comment) {
        getStartedBibs(password).add(bibNumber);

        var entry = new DnsEntry();
        entry.setPassword(password);
        entry.setCompetitorNumber(bibNumber);
        entry.setRegisteredAt(LocalDateTime.now());
        entry.setRegisteredBy(registeredBy);
        entry.setComment(comment);
        repository.save(entry);

        getChangeSignal(password).incrementBy(1);
        notifyListeners(new StartedEvent(password, bibNumber, registeredBy, true));
    }

    public void updateComment(String password, int bibNumber, String comment) {
        getEntry(password, bibNumber).ifPresent(entry -> {
            entry.setComment(comment);
            repository.save(entry);
            getChangeSignal(password).incrementBy(1);
        });
    }

    public void unmarkStarted(String password, int bibNumber) {
        getStartedBibs(password).remove(bibNumber);

        repository.findByPassword(password).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst()
                .ifPresent(repository::delete);

        getChangeSignal(password).incrementBy(1);
        notifyListeners(new StartedEvent(password, bibNumber, null, false));
    }

    public boolean isStarted(String password, int bibNumber) {
        return getStartedBibs(password).contains(bibNumber);
    }

    public Optional<DnsEntry> getEntry(String password, int bibNumber) {
        return repository.findByPassword(password).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst();
    }

    /**
     * Returns all DnsEntries for a competition, keyed by competitor number.
     */
    public Map<Integer, DnsEntry> getEntries(String password) {
        return repository.findByPassword(password).stream()
                .collect(Collectors.toMap(DnsEntry::getCompetitorNumber, e -> e, (a, b) -> a));
    }

    public void addStartedListener(Consumer<StartedEvent> listener) {
        startedListeners.add(listener);
    }

    public void removeStartedListener(Consumer<StartedEvent> listener) {
        startedListeners.remove(listener);
    }

    private void notifyListeners(StartedEvent event) {
        for (var listener : startedListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // ignore listener errors
            }
        }
    }

    public void clearCache() {
        startedCache.clear();
        changeCounters.clear();
    }

    private Set<Integer> loadFromDb(String password) {
        return repository.findByPassword(password).stream()
                .map(DnsEntry::getCompetitorNumber)
                .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));
    }
}
