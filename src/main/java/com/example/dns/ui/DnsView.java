package com.example.dns.ui;

import com.example.dns.service.DnsService;
import com.example.dns.service.TulospalveluService;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import org.orienteering.datastandard._3.StartList;
import org.vaadin.firitin.util.VStyleUtil;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Route(value = "dns")
public class DnsView extends VerticalLayout implements HasUrlParameter<String> {

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final TulospalveluService tulospalveluService;
    private final DnsService dnsService;
    private final UserSession userSession;
    private final Map<LocalTime, StartTimeSlot> slotsByTime = new LinkedHashMap<>();
    private final Set<String> allStartPlaces = new TreeSet<>();

    private String competitionId;
    private Set<String> selectedStartPlaces = Set.of();
    private String nameFilter = "";
    private String numberFilter = "";
    private boolean showStarted = true;
    private boolean showNotStarted = true;

    public DnsView(TulospalveluService tulospalveluService, DnsService dnsService,
                   UserSession userSession) {
        this.tulospalveluService = tulospalveluService;
        this.dnsService = dnsService;
        this.userSession = userSession;
        setWidthFull();
        getStyle().set("--vaadin-card-title-font-size", "var(--aura-font-size-xl)");
    }

    @Override
    public void setParameter(BeforeEvent event, String competitionId) {
        this.competitionId = competitionId;
        removeAll();
        slotsByTime.clear();
        allStartPlaces.clear();

        add(new H1("DNS - " + competitionId));

        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return;
        }

        Set<Integer> startedBibs = dnsService.getStartedBibs(competitionId);

        var sortedTimes = new TreeMap<LocalTime, StartTimeSlot>();
        for (var classStart : startList.getClassStart()) {
            for (var personStart : classStart.getPersonStart()) {
                for (var raceStart : personStart.getStart()) {
                    LocalTime time = toLocalTime(raceStart.getStartTime());
                    if (time != null) {
                        sortedTimes.computeIfAbsent(time, StartTimeSlot::new);
                    }
                }
            }
        }

        for (var classStart : startList.getClassStart()) {
            String className = classStart.getClazz() != null
                    ? classStart.getClazz().getName() : "";

            String startPlace = "";
            if (!classStart.getStartName().isEmpty()) {
                startPlace = classStart.getStartName().getFirst().getValue();
            }
            if (startPlace != null && !startPlace.isBlank()) {
                allStartPlaces.add(startPlace);
            }

            for (var personStart : classStart.getPersonStart()) {
                for (var raceStart : personStart.getStart()) {
                    LocalTime time = toLocalTime(raceStart.getStartTime());
                    if (time == null) {
                        continue;
                    }
                    int bib = parseBibNumber(raceStart.getBibNumber());
                    var slot = sortedTimes.get(time);
                    if (slot != null) {
                        var card = new RunnerCard(personStart, className, bib, startPlace, time, competitionId, dnsService);
                        if (startedBibs.contains(bib)) {
                            card.setStarted(true);
                        }
                        card.addCardClickListener(this::onCardClicked);
                        slot.addRunner(card);
                    }
                }
            }
        }

        slotsByTime.putAll(sortedTimes);
        slotsByTime.values().forEach(StartTimeSlot::sortRunners);
        slotsByTime.values().forEach(this::add);
    }

    void onCardClicked(RunnerCard card) {
        // Check server-side state for concurrency
        boolean serverStarted = dnsService.isStarted(competitionId, card.getBibNumber());

        if (serverStarted) {
            // Sync UI if out of date
            card.setStarted(true);

            var dialog = new ConfirmDialog(
                    "Peru lähtömerkintä?",
                    card.getName() + " (nro " + card.getBibNumber() + ") on merkitty lähteneeksi. Haluatko perua merkinnän?",
                    "Peru merkintä",
                    e -> {
                        dnsService.unmarkStarted(competitionId, card.getBibNumber());
                        card.setStarted(false);
                    });
            dialog.setCancelable(true);
            dialog.setCancelText("Peruuta");
            dialog.setConfirmButtonTheme("error primary");
            dialog.open();
        } else {
            dnsService.markStarted(competitionId, card.getBibNumber(), userSession.getName());
            card.setStarted(true);
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        findAncestor(TopLayout.class).initFilters(this);
    }

    public Map<LocalTime, StartTimeSlot> getSlotsByTime() {
        return slotsByTime;
    }

    void scrollToTime(LocalTime time) {
        // Find the closest slot at or after the requested time
        var slot = slotsByTime.get(time);
        if (slot == null) {
            slot = slotsByTime.entrySet().stream()
                    .filter(e -> !e.getKey().isBefore(time))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (slot != null) {
            slot.scrollIntoView();
        }
    }

    public Set<String> getAllStartPlaces() {
        return allStartPlaces;
    }

    void applyFilters(Set<String> startPlaces, String name, String number,
                      boolean showStarted, boolean showNotStarted) {
        this.selectedStartPlaces = startPlaces;
        this.nameFilter = name != null ? name.toLowerCase().trim() : "";
        this.numberFilter = number != null ? number.trim() : "";
        this.showStarted = showStarted;
        this.showNotStarted = showNotStarted;

        for (var entry : slotsByTime.entrySet()) {
            StartTimeSlot slot = entry.getValue();

            boolean anyRunnerVisible = false;
            for (RunnerCard card : slot.getRunnerCards()) {
                boolean visible = matchesRunner(card);
                card.setVisible(visible);
                if (visible) {
                    anyRunnerVisible = true;
                }
            }

            slot.setVisible(anyRunnerVisible);
        }
    }

    private boolean matchesRunner(RunnerCard card) {
        if (card.isStarted() && !showStarted) {
            return false;
        }
        if (!card.isStarted() && !showNotStarted) {
            return false;
        }
        if (!selectedStartPlaces.isEmpty() && !card.getStartPlace().isEmpty()) {
            if (!selectedStartPlaces.contains(card.getStartPlace())) {
                return false;
            }
        }
        if (!nameFilter.isEmpty()) {
            if (!card.getName().toLowerCase().contains(nameFilter)) {
                return false;
            }
        }
        if (!numberFilter.isEmpty()) {
            if (!String.valueOf(card.getBibNumber()).contains(numberFilter)) {
                return false;
            }
        }
        return true;
    }

    private static LocalTime toLocalTime(XMLGregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        return LocalTime.of(cal.getHour(), cal.getMinute(), cal.getSecond());
    }

    private static int parseBibNumber(String bib) {
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
