package com.example.dns.service;

import org.orienteering.datastandard._3.StartList;
import org.springframework.stereotype.Service;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalTime;
import java.util.Optional;

@Service
public class StartListLookupService {

    public record RunnerInfo(int bibNumber, LocalTime startTime, String name, String className) {
    }

    private final TulospalveluService tulospalveluService;

    public StartListLookupService(TulospalveluService tulospalveluService) {
        this.tulospalveluService = tulospalveluService;
    }

    public Optional<RunnerInfo> findByBib(String competitionId, int bib) {
        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return Optional.empty();
        }

        for (var classStart : startList.getClassStart()) {
            String className = classStart.getClazz() != null
                    ? classStart.getClazz().getName() : "";
            for (var personStart : classStart.getPersonStart()) {
                for (var raceStart : personStart.getStart()) {
                    int bibNumber = parseBib(raceStart.getBibNumber());
                    if (bibNumber == bib) {
                        return Optional.of(toRunnerInfo(personStart, className, bibNumber, raceStart.getStartTime()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    public Optional<RunnerInfo> findByControlCard(String competitionId, String cc) {
        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return Optional.empty();
        }

        for (var classStart : startList.getClassStart()) {
            String className = classStart.getClazz() != null
                    ? classStart.getClazz().getName() : "";
            for (var personStart : classStart.getPersonStart()) {
                for (var raceStart : personStart.getStart()) {
                    if (raceStart.getControlCard() != null) {
                        for (var controlCard : raceStart.getControlCard()) {
                            if (cc.equals(controlCard.getValue())) {
                                int bibNumber = parseBib(raceStart.getBibNumber());
                                return Optional.of(toRunnerInfo(personStart, className, bibNumber, raceStart.getStartTime()));
                            }
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static RunnerInfo toRunnerInfo(org.orienteering.datastandard._3.PersonStart personStart,
                                           String className, int bibNumber,
                                           XMLGregorianCalendar startTimeCal) {
        String name = "?";
        if (personStart.getPerson() != null && personStart.getPerson().getName() != null) {
            var n = personStart.getPerson().getName();
            name = n.getGiven() + " " + n.getFamily();
        }
        LocalTime startTime = toLocalTime(startTimeCal);
        return new RunnerInfo(bibNumber, startTime, name, className);
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
