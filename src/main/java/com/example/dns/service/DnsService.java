package com.example.dns.service;

import com.example.dns.domain.DnsEntry;
import com.example.dns.domain.DnsEntryRepository;
import com.vaadin.flow.signals.shared.SharedNumberSignal;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class DnsService {

    private final DnsEntryRepository repository;
    private final Map<String, Set<Integer>> startedCache = new ConcurrentHashMap<>();
    private final Map<String, SharedNumberSignal> changeCounters = new ConcurrentHashMap<>();

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
        getStartedBibs(password).add(bibNumber);

        var entry = new DnsEntry();
        entry.setPassword(password);
        entry.setCompetitorNumber(bibNumber);
        entry.setRegisteredAt(LocalDateTime.now());
        entry.setRegisteredBy(registeredBy);
        repository.save(entry);

        getChangeSignal(password).incrementBy(1);
    }

    public void unmarkStarted(String password, int bibNumber) {
        getStartedBibs(password).remove(bibNumber);

        repository.findByPassword(password).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst()
                .ifPresent(repository::delete);

        getChangeSignal(password).incrementBy(1);
    }

    public boolean isStarted(String password, int bibNumber) {
        return getStartedBibs(password).contains(bibNumber);
    }

    public Optional<DnsEntry> getEntry(String password, int bibNumber) {
        return repository.findByPassword(password).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst();
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
