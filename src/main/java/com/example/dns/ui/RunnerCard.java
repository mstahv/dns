package com.example.dns.ui;

import com.example.dns.domain.DnsEntry;
import com.example.dns.service.DnsService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.function.SerializableConsumer;
import org.orienteering.datastandard._3.PersonStart;
import org.vaadin.firitin.components.popover.PopoverButton;
import org.vaadin.firitin.layouts.HorizontalFloatLayout;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class RunnerCard extends Card {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PersonStart personStart;
    private final String className;
    private final int bibNumber;
    private final String startPlace;
    private final LocalTime startTime;
    private final LocalDateTime startDateTime;
    private final String password;
    private final DnsService dnsService;
    private final String registeredBy;
    private boolean started;

    public RunnerCard(PersonStart personStart, String className, int bibNumber,
                      String startPlace, LocalTime startTime, LocalDateTime startDateTime,
                      String password, DnsService dnsService, String registeredBy) {
        this.personStart = personStart;
        this.className = className;
        this.bibNumber = bibNumber;
        this.startPlace = startPlace != null ? startPlace : "";
        this.startTime = startTime;
        this.startDateTime = startDateTime;
        this.password = password;
        this.dnsService = dnsService;
        this.registeredBy = registeredBy;

        setTitle(bibNumber + " " + getName());
        setTitleHeadingLevel(3);

        var badges = new BadgeRow();

        var classBadge = new Badge(className);
        classBadge.addThemeVariants(BadgeVariant.SUCCESS, BadgeVariant.FILLED);

        var bibBadge = new Badge("nro: " + bibNumber);
        bibBadge.addThemeVariants(BadgeVariant.WARNING, BadgeVariant.FILLED);

        badges.add(classBadge, bibBadge);

        String club = getClub();
        if (!club.isEmpty()) {
            var clubBadge = new Badge(club);
            clubBadge.addThemeVariants(BadgeVariant.FILLED);
            badges.add(clubBadge);
        }

        add(badges);

        var detailsButton = new PopoverButton(this::createDetailsContent){{
            // WTF Vaadin Button 🤦‍♂️
            getElement().addEventListener("click", ignore -> {}).stopPropagation();
            setIcon(VaadinIcon.INFO.create());
            addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.SMALL);
            getStyle().setPosition(Style.Position.ABSOLUTE);
            getStyle().setRightPx(0);
            getStyle().setBottomPx(0);
        }};
        add(detailsButton);

        getStyle().setCursor("pointer");
        updateAppearance();
    }

    private Component createDetailsContent() {
        var layout = new VerticalLayout();
        layout.setPadding(true);
        layout.setSpacing(false);
        layout.setWidth("280px");

        layout.add(detail("Nimi", getName()));
        layout.add(detail("Seura", getClub()));
        layout.add(detail("Sarja", className));
        layout.add(detail("Kilpailijanumero", String.valueOf(bibNumber)));
        layout.add(detail("Lähtöaika", startTime != null ? startTime.format(TIME_FMT) : "-"));
        layout.add(detail("Lähtöpaikka", startPlace.isEmpty() ? "-" : startPlace));

        if (personStart.getStart() != null && !personStart.getStart().isEmpty()) {
            var raceStart = personStart.getStart().getFirst();
            if (raceStart.getControlCard() != null && !raceStart.getControlCard().isEmpty()) {
                layout.add(detail("Emit", raceStart.getControlCard().getFirst().getValue()));
            }
        }

        var entry = dnsService.getEntry(password, bibNumber);
        if (entry.isPresent()) {
            DnsEntry e = entry.get();
            layout.add(detail("Kirjattu lähteneeksi",
                    e.getRegisteredAt() != null ? e.getRegisteredAt().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : "-"));
            layout.add(detail("Kirjaaja",
                    e.getRegisteredBy() != null ? e.getRegisteredBy() : "-"));

            var commentField = new CommentField(e.getComment());
            commentField.addSaveListener(comment -> {
                dnsService.updateComment(password, bibNumber, comment);
                Notification.show("Kommentti tallennettu");
            });
            layout.add(commentField);
        } else {
            layout.add(detail("Kirjattu lähteneeksi", "Ei"));

            var commentField = new CommentField(null);
            var markButton = new Button("Kirjaa lähteneeksi kommentilla...", VaadinIcon.CHECK.create(), e -> {
                dnsService.markStarted(password, bibNumber, registeredBy, commentField.getValue());
                setStarted(true);
                Notification.show("Kirjattu lähteneeksi");
            });
            markButton.addThemeVariants(ButtonVariant.PRIMARY, ButtonVariant.SMALL);
            markButton.setWidthFull();
            layout.add(commentField, markButton);
        }

        return layout;
    }

    static class CommentField extends VerticalLayout {

        private final TextArea textArea;
        private SerializableConsumer<String> saveListener;

        CommentField(String initialValue) {
            setPadding(false);
            setSpacing(false);

            textArea = new TextArea("Kommentti");
            textArea.setWidthFull();
            textArea.setMaxLength(500);
            textArea.setPlaceholder("Esim. annettu emit 12345 rikkoutuneen tilalle");
            if (initialValue != null) {
                textArea.setValue(initialValue);
            }

            var saveButton = new Button("Tallenna kommentti", e -> {
                if (saveListener != null) {
                    saveListener.accept(textArea.getValue());
                }
            });
            saveButton.addThemeVariants(ButtonVariant.SMALL);
            saveButton.setWidthFull();

            add(textArea, saveButton);
        }

        String getValue() {
            return textArea.getValue();
        }

        void addSaveListener(SerializableConsumer<String> listener) {
            this.saveListener = listener;
        }
    }

    private static Component detail(String label, String value) {
        var row = new HorizontalLayout();
        row.setWidthFull();
        row.setPadding(false);

        var labelSpan = new Span(label + ":");
        labelSpan.getStyle().setFontWeight("bold");
        labelSpan.setWidth("50%");

        var valueSpan = new Span(value != null ? value : "-");

        row.add(labelSpan, valueSpan);
        return row;
    }

    public void addCardClickListener(SerializableConsumer<RunnerCard> listener) {
        getElement().addEventListener("click", e -> listener.accept(this));
    }

    public void setStarted(boolean started) {
        this.started = started;
        updateAppearance();
    }

    public boolean isStarted() {
        return started;
    }

    public void updateAppearance() {
        if (started) {
            removeClassName("not-started");
            addClassName("started");
        } else {
            removeClassName("started");
            addClassName("not-started");
        }
    }

    public PersonStart getPersonStart() {
        return personStart;
    }

    public int getBibNumber() {
        return bibNumber;
    }

    public String getClassName() {
        return className;
    }

    public String getStartPlace() {
        return startPlace;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public String getName() {
        if (personStart.getPerson() == null || personStart.getPerson().getName() == null) {
            return "?";
        }
        var name = personStart.getPerson().getName();
        return name.getGiven() + " " + name.getFamily();
    }

    public String getClub() {
        if (personStart.getOrganisation() == null) {
            return "";
        }
        return personStart.getOrganisation().getShortName() != null
                ? personStart.getOrganisation().getShortName()
                : personStart.getOrganisation().getName();
    }

    private static class BadgeRow extends HorizontalFloatLayout {{
        setPadding(false);
    }}
}
