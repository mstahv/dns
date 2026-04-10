package com.example.dns.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface MachineReadingRepository extends ListCrudRepository<MachineReading, Long> {

    List<MachineReading> findTop100ByCompetitionIdOrderByReadAtDesc(String competitionId);
}
