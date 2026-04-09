package com.example.dns.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface DnsEntryRepository extends ListCrudRepository<DnsEntry, Long> {

    List<DnsEntry> findByCompetitionId(String competitionId);
}
