package com.example.dns.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompetitionMachineRepository extends JpaRepository<CompetitionMachine, Long> {

    Optional<CompetitionMachine> findByPasswordAndMachine(String password, Machine machine);

    List<CompetitionMachine> findByPassword(String password);

    List<CompetitionMachine> findByMachine(Machine machine);
}
