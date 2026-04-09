package com.example.dns.service;

import com.example.dns.domain.DnsEntry;
import com.example.dns.domain.DnsEntryRepository;
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

    // competitionId -> set of started bib numbers
    private final Map<String, Set<Integer>> startedCache = new ConcurrentHashMap<>();

    public DnsService(DnsEntryRepository repository) {
        this.repository = repository;
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
    }

    public void unmarkStarted(String competitionId, int bibNumber) {
        getStartedBibs(competitionId).remove(bibNumber);

        repository.findByCompetitionId(competitionId).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst()
                .ifPresent(repository::delete);
    }

    public boolean isStarted(String competitionId, int bibNumber) {
        return getStartedBibs(competitionId).contains(bibNumber);
    }

    public Optional<DnsEntry> getEntry(String competitionId, int bibNumber) {
        return repository.findByCompetitionId(competitionId).stream()
                .filter(e -> e.getCompetitorNumber() == bibNumber)
                .findFirst();
    }

    private Set<Integer> loadFromDb(String competitionId) {
        return repository.findByCompetitionId(competitionId).stream()
                .map(DnsEntry::getCompetitorNumber)
                .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));
    }
}
