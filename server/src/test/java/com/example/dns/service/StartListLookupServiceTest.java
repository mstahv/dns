package com.example.dns.service;

import com.example.dns.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StartListLookupServiceTest {

    private static final String COMPETITION_ID = "2026_viking";

    @Autowired
    StartListLookupService lookupService;

    @Autowired
    TulospalveluService tulospalveluService;

    @Test
    void findByBib_returnsRunnerWhenExists() {
        // Get the start list and find a valid bib
        var startList = tulospalveluService.getStartList(COMPETITION_ID);
        assertNotNull(startList, "Start list should be available for test competition");

        // Pick the first runner's bib
        int testBib = 0;
        outer:
        for (var cs : startList.getClassStart()) {
            for (var ps : cs.getPersonStart()) {
                for (var rs : ps.getStart()) {
                    if (rs.getBibNumber() != null && !rs.getBibNumber().isBlank()) {
                        testBib = Integer.parseInt(rs.getBibNumber().trim());
                        break outer;
                    }
                }
            }
        }
        assertTrue(testBib > 0, "Should find at least one runner with a bib number");

        var result = lookupService.findByBib(COMPETITION_ID, testBib);
        assertTrue(result.isPresent());
        assertEquals(testBib, result.get().bibNumber());
        assertNotNull(result.get().name());
        assertNotNull(result.get().className());
    }

    @Test
    void findByBib_returnsEmptyForNonExistentBib() {
        var result = lookupService.findByBib(COMPETITION_ID, 99999);
        assertTrue(result.isEmpty());
    }

    @Test
    void findByControlCard_returnsEmptyForNonExistentCc() {
        var result = lookupService.findByControlCard(COMPETITION_ID, "00000");
        assertTrue(result.isEmpty());
    }

    @Test
    void findByBib_returnsEmptyForInvalidCompetition() {
        var result = lookupService.findByBib("nonexistent_comp", 1);
        assertTrue(result.isEmpty());
    }
}
