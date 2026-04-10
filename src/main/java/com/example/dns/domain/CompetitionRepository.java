package com.example.dns.domain;

import org.springframework.data.repository.ListCrudRepository;

import java.util.List;

public interface CompetitionRepository extends ListCrudRepository<Competition, String> {

    List<Competition> findByCompetitionId(String competitionId);
}
