package com.example.dns.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

public interface CompetitionRepository extends ListCrudRepository<Competition, String> {

    Optional<Competition> findByPassword(String password);
}
