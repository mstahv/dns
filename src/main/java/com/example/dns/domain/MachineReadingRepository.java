package com.example.dns.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MachineReadingRepository extends JpaRepository<MachineReading, Long> {

    List<MachineReading> findTop100ByPasswordOrderByReadAtDesc(String password);

    List<MachineReading> findByPasswordAndFoundFalseOrderByReadAtDesc(String password);
}
