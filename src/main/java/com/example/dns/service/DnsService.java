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
    public SharedNumberSignal getChangeSignal(String competitionId) {
        return changeCounters.computeIfAbsent(competitionId, k -> new SharedNumberSignal());
    }

    public Set<Integer> getStartedBibs(String competitionId) {
        return startedCache.computeIfAbsent(competitionId, this::loadFromDb);
    }

    public void markStarted(String competitionId, int bibNumber, String registeredBy) {
        getStartedBibs(competitionId).add(bibNumber);

        var entry = new DnsEntry();
        entry.setCompetitionId(competitionId);
        entry.setCompetitorNumber(bibNumber);
        entry.setRegisteredAt(LocalDateTime.now());
        entry.setRegisteredBy(registeredBy);
        repository.save(entry);

        getChangeSignal(competitionId).incrementBy(1);
    }

    public void unmarkStarted(String competitionId, int bibNumber) {
        getStartedBibs(competitionId).remove(bibNumber);

        repository.findByCompetitionId(competitionId).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst()
                .ifPresent(repository::delete);

        getChangeSignal(competitionId).incrementBy(1);
    }

    public boolean isStarted(String competitionId, int bibNumber) {
        return getStartedBibs(competitionId).contains(bibNumber);
    }

    public Optional<DnsEntry> getEntry(String competitionId, int bibNumber) {
        return repository.findByCompetitionId(competitionId).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst();
    }

    public void clearCache() {
        startedCache.clear();
        changeCounters.clear();
    }

    private Set<Integer> loadFromDb(String competitionId) {
        return repository.findByCompetitionId(competitionId).stream()
                .map(DnsEntry::getCompetitorNumber)
                .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));
    }
}
