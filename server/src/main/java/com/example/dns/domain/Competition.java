package com.example.dns.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "competition")
public class Competition implements Persistable<String> {

    @Id
    private String password;

    @Column(nullable = false)
    private String competitionId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int stage = 1;

    /**
     * Optional direct URL to an IOF XML start list. When set, this overrides
     * the URL constructed from competitionId + stage. Lets users register
     * competitions hosted outside tulospalvelu.fi.
     */
    @Column(length = 1024)
    private String startListUrl;

    @Transient
    private boolean isNew = true;

    @Override
    public String getId() {
        return password;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCompetitionId() {
        return competitionId;
    }

    public void setCompetitionId(String competitionId) {
        this.competitionId = competitionId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStage() {
        return stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }

    public String getStartListUrl() {
        return startListUrl;
    }

    public void setStartListUrl(String startListUrl) {
        this.startListUrl = startListUrl;
    }
}
