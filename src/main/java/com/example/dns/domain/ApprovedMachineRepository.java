package com.example.dns.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovedMachineRepository extends ListCrudRepository<ApprovedMachine, Long> {

    Optional<ApprovedMachine> findByCompetitionIdAndMachineId(String competitionId, String machineId);

    List<ApprovedMachine> findByCompetitionId(String competitionId);
}
