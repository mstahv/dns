package com.example.dns.api;

import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.DnsService;
import com.example.dns.service.StartListLookupService;
import com.example.dns.service.TulospalveluService;
import org.orienteering.datastandard._3.StartList;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/started")
public class DnsRestController {

    private final CompetitionRepository competitionRepository;
    private final DnsService dnsService;
    private final TulospalveluService tulospalveluService;

    public DnsRestController(CompetitionRepository competitionRepository,
                             DnsService dnsService,
                             TulospalveluService tulospalveluService) {
        this.competitionRepository = competitionRepository;
        this.dnsService = dnsService;
        this.tulospalveluService = tulospalveluService;
    }

    @PostMapping(consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> markStarted(
            @RequestHeader("X-Competition-Password") String password,
            @RequestHeader("X-Registered-By") String registeredBy,
            @RequestBody String body) {

        var competition = competitionRepository.findById(password);
        if (competition.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid competition password");
        }
        if (!competition.get().isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Kilpailu on päättynyt");
        }

        int[] bibs = parseBibs(body);
        int count = 0;

        for (int bib : bibs) {
            if (!dnsService.isStarted(password, bib)) {
                dnsService.markStarted(password, bib, registeredBy);
                count++;
            }
        }

        return ResponseEntity.ok(count + " runners marked as started");
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getStarted(
            @RequestHeader("X-Competition-Password") String password,
            @RequestParam("times") String times) {

        var competition = competitionRepository.findById(password);
        if (competition.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid competition password");
        }

        String competitionId = competition.get().getCompetitionId();
        Set<LocalTime> requestedTimes = parseTimesParam(times);

        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return ResponseEntity.ok("");
        }

        String result = startList.getClassStart().stream()
                .filter(cs -> !StartListLookupService.isIgnoredClass(cs))
                .flatMap(cs -> cs.getPersonStart().stream())
                .flatMap(ps -> ps.getStart().stream())
                .filter(rs -> {
                    LocalTime time = toLocalTime(rs.getStartTime());
                    return time != null && requestedTimes.contains(time);
                })
                .filter(rs -> {
                    int bib = parseBib(rs.getBibNumber());
                    return bib > 0 && dnsService.isStarted(password, bib);
                })
                .map(rs -> rs.getBibNumber().trim())
                .collect(Collectors.joining(","));

        return ResponseEntity.ok(result);
    }

    private static int[] parseBibs(String body) {
        return Arrays.stream(body.trim().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static Set<LocalTime> parseTimesParam(String times) {
        return Arrays.stream(times.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replace('.', ':'))
                .map(LocalTime::parse)
                .collect(Collectors.toSet());
    }

    private static LocalTime toLocalTime(XMLGregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        return LocalTime.of(cal.getHour(), cal.getMinute(), cal.getSecond());
    }

    private static int parseBib(String bib) {
        if (bib == null || bib.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(bib.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
