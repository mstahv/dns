package com.example.dns.ui;

import com.example.dns.service.DnsService;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.function.SerializableConsumer;
import org.orienteering.datastandard._3.PersonStart;
import org.vaadin.firitin.components.cssgrid.CssGrid;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class StartTimeSlot extends Composite<Div> {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final LocalTime startTime;
    private final CssGrid runners = new RunnersGrid();
    private final List<RunnerCard> runnerCards = new ArrayList<>();
    private final List<PendingRunner> pendingRunners = new ArrayList<>();
    private boolean materialized;

    public StartTimeSlot(LocalTime startTime) {
        this.startTime = startTime;

        var timeLabel = new Span(startTime.format(TIME_FORMAT));
        timeLabel.getStyle()
                .setFontWeight("bold")
                .setFontSize("1.1em");

        var spinner = new Div();
        spinner.addClassName("slot-spinner");

        getContent().add(timeLabel, spinner, runners);
        getContent().setWidthFull();
        getContent().addClassName("pending-slot");
        getContent().getStyle()
                .setMinHeight("300px")
                .setPadding("8px")
                .setBorderBottom("1px solid #e0e0e0");
    }

    public void addPendingRunner(PersonStart personStart, String className, int bibNumber,
                                 String startPlace, LocalDateTime startDateTime) {
        pendingRunners.add(new PendingRunner(personStart, className, bibNumber, startPlace, startDateTime));
    }

    public void materialize(String password, DnsService dnsService, String registeredBy,
                            Set<Integer> startedBibs,
                            SerializableConsumer<RunnerCard> clickListener) {
        if (materialized) {
            return;
        }
        materialized = true;
        getContent().removeClassName("pending-slot");
        getContent().getStyle().remove("min-height");
        // Remove spinner
        getContent().getChildren()
                .filter(c -> c instanceof Div d && d.hasClassName("slot-spinner"))
                .findFirst()
                .ifPresent(getContent()::remove);

        // Mark slot as past if its start time is in the past
        boolean isPast = pendingRunners.stream()
                .map(PendingRunner::startDateTime)
                .filter(dt -> dt != null)
                .anyMatch(dt -> LocalDateTime.now().isAfter(dt));
        if (isPast) {
            getContent().addClassName("past-slot");
        }

        pendingRunners.sort(Comparator.comparing(p ->
                formatName(p.personStart())));

        for (var pending : pendingRunners) {
            var card = new RunnerCard(pending.personStart(), pending.className(),
                    pending.bibNumber(), pending.startPlace(), startTime,
                    pending.startDateTime(), password, dnsService, registeredBy);
            if (startedBibs.contains(pending.bibNumber())) {
                card.setStarted(true);
            }
            card.addCardClickListener(clickListener);
            runnerCards.add(card);
            runners.add(card);
        }
    }

    public boolean isMaterialized() {
        return materialized;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public List<RunnerCard> getRunnerCards() {
        return runnerCards;
    }

    private static String formatName(PersonStart ps) {
        if (ps.getPerson() == null || ps.getPerson().getName() == null) {
            return "?";
        }
        var name = ps.getPerson().getName();
        return name.getGiven() + " " + name.getFamily();
    }

    record PendingRunner(PersonStart personStart, String className,
                         int bibNumber, String startPlace, LocalDateTime startDateTime) {
    }

    private static class RunnersGrid extends CssGrid {{
        setTemplateColumns("repeat(auto-fill, minmax(220px, 1fr))");
        setGap("8px");
        setWidthFull();
    }}
}
