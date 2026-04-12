package com.example.dns.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DnsEntryRepository extends JpaRepository<DnsEntry, Long> {

    List<DnsEntry> findByPassword(String password);
}
