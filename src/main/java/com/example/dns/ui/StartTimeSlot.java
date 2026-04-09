package com.example.dns.ui;

import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.vaadin.firitin.components.cssgrid.CssGrid;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StartTimeSlot extends Composite<Div> {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final LocalTime startTime;
    private final CssGrid runners = new RunnersGrid();
    private final List<RunnerCard> runnerCards = new ArrayList<>();

    public StartTimeSlot(LocalTime startTime) {
        this.startTime = startTime;

        var timeLabel = new Span(startTime.format(TIME_FORMAT));
        timeLabel.getStyle()
                .setFontWeight("bold")
                .setFontSize("1.1em");

        getContent().add(timeLabel, runners);
        getContent().setWidthFull();
        getContent().getStyle()
                .setPadding("8px")
                .setBorderBottom("1px solid #e0e0e0");
    }

    public void addRunner(RunnerCard card) {
        runnerCards.add(card);
        runners.add(card);
    }

    public void sortRunners() {
        runnerCards.sort(Comparator.comparing(RunnerCard::getName));
        runners.removeAll();
        runnerCards.forEach(runners::add);
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public List<RunnerCard> getRunnerCards() {
        return runnerCards;
    }

    private static class RunnersGrid extends CssGrid {{
        setTemplateColumns("repeat(auto-fill, minmax(220px, 1fr))");
        setGap("8px");
        setWidthFull();
    }}
}
