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
import org.vaadin.firitin.util.IntersectionObserver;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

@Route(value = "dns")
public class DnsView extends VerticalLayout implements HasUrlParameter<String> {

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int INITIAL_SLOTS = 10;

    private final TulospalveluService tulospalveluService;
    private final DnsService dnsService;
    private final UserSession userSession;
    private final Map<LocalTime, StartTimeSlot> slotsByTime = new LinkedHashMap<>();
    private final List<StartTimeSlot> slotList = new ArrayList<>();
    private final Set<String> allStartPlaces = new TreeSet<>();

    private String competitionId;
    private Set<Integer> startedBibs;
    private int materializedCount;
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
        slotList.clear();
        allStartPlaces.clear();
        materializedCount = 0;

        add(new H1("DNS - " + competitionId));

        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return;
        }

        startedBibs = dnsService.getStartedBibs(competitionId);

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
                        slot.addPendingRunner(personStart, className, bib, startPlace,
                                toLocalDateTime(raceStart.getStartTime()));
                    }
                }
            }
        }

        slotsByTime.putAll(sortedTimes);
        slotList.addAll(sortedTimes.values());
        slotList.forEach(this::add);

        // Materialize first batch immediately
        materializeMore(INITIAL_SLOTS);
    }

    private void materializeMore(int targetTotal) {
        int end = Math.min(targetTotal, slotList.size());
        int previousCount = materializedCount;
        for (int i = materializedCount; i < end; i++) {
            slotList.get(i).materialize(competitionId, dnsService, startedBibs,
                    this::onCardClicked);
        }
        materializedCount = Math.max(materializedCount, end);

        for (int i = previousCount; i < materializedCount; i++) {
            applyFiltersToSlot(slotList.get(i));
        }
    }

    private void applyFiltersToSlot(StartTimeSlot slot) {
        if (!slot.isMaterialized()) {
            return;
        }
        for (RunnerCard card : slot.getRunnerCards()) {
            card.setVisible(matchesRunner(card));
        }
    }

    private void ensureMaterializedFrom(int fromIndex) {
        int target = Math.min(fromIndex + INITIAL_SLOTS, slotList.size());
        if (target > materializedCount) {
            materializeMore(target);
        }
    }

    void onCardClicked(RunnerCard card) {
        boolean serverStarted = dnsService.isStarted(competitionId, card.getBibNumber());

        if (serverStarted) {
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
        observeUnmaterializedSlots();
    }

    private void observeUnmaterializedSlots() {
        var observer = IntersectionObserver.get();
        for (int i = materializedCount; i < slotList.size(); i++) {
            var slot = slotList.get(i);
            int index = i;
            observer.observe(slot, entry -> {
                if (entry.isIntersecting()) {
                    ensureMaterializedFrom(index);
                    observer.unobserve(slot);
                }
            });
        }
    }

    // Exposed for tests
    void onVisibleSlotChanged(int slotIndex) {
        ensureMaterializedFrom(slotIndex);
    }

    public Map<LocalTime, StartTimeSlot> getSlotsByTime() {
        return slotsByTime;
    }

    void scrollToTime(LocalTime time) {
        StartTimeSlot slot = slotsByTime.get(time);
        int targetIndex = -1;
        if (slot == null) {
            for (int i = 0; i < slotList.size(); i++) {
                if (!slotList.get(i).getStartTime().isBefore(time)) {
                    slot = slotList.get(i);
                    targetIndex = i;
                    break;
                }
            }
        } else {
            targetIndex = slotList.indexOf(slot);
        }
        if (slot != null) {
            ensureMaterializedFrom(targetIndex);
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

        // Text search needs all slots materialized
        if (!nameFilter.isEmpty() || !numberFilter.isEmpty()) {
            materializeMore(slotList.size());
        }

        for (StartTimeSlot slot : slotList) {
            applyFiltersToSlot(slot);
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

    private static LocalDateTime toLocalDateTime(XMLGregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        return LocalDateTime.of(
                cal.getYear(), cal.getMonth(), cal.getDay(),
                cal.getHour(), cal.getMinute(), cal.getSecond());
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
