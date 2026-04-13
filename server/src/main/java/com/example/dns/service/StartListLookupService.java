package com.example.dns.service;

import org.orienteering.datastandard._3.ClassStart;
import org.orienteering.datastandard._3.StartList;
import org.springframework.stereotype.Service;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class StartListLookupService {

    public record RunnerInfo(int bibNumber, LocalTime startTime, String name, String className) {
    }

    public record ControlCardEntry(int bib, String st) {
    }

    /**
     * Builds a compact map from control card number to {bib, startTime}
     * for all runners in a competition.
     */
    public Map<String, ControlCardEntry> buildControlCardMap(String competitionId) {
        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return Map.of();
        }
        Map<String, ControlCardEntry> map = new LinkedHashMap<>();
        for (var classStart : startList.getClassStart()) {
            if (isIgnoredClass(classStart)) continue;
            for (var personStart : classStart.getPersonStart()) {
                for (var raceStart : personStart.getStart()) {
                    int bib = parseBib(raceStart.getBibNumber());
                    LocalTime st = toLocalTime(raceStart.getStartTime());
                    if (raceStart.getControlCard() != null) {
                        for (var cc : raceStart.getControlCard()) {
                            if (cc.getValue() != null && !cc.getValue().isBlank()) {
                                map.put(cc.getValue(),
                                        new ControlCardEntry(bib, st != null ? st.toString() : ""));
                            }
                        }
                    }
                }
            }
        }
        return map;
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
            if (isIgnoredClass(classStart)) continue;
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
            if (isIgnoredClass(classStart)) continue;
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

    /**
     * Returns true if the class should be ignored when processing start lists.
     * VAKANTIT class contains placeholder entries, not real competitors.
     */
    public static boolean isIgnoredClass(ClassStart classStart) {
        if (classStart.getClazz() == null) return false;
        String name = classStart.getClazz().getName();
        return "VAKANTIT".equalsIgnoreCase(name);
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
