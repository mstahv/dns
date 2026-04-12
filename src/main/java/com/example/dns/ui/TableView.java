package com.example.dns.ui;

import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.DnsService;
import com.example.dns.service.TulospalveluService;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.ElementEffect;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.signals.shared.SharedNumberSignal;
import org.orienteering.datastandard._3.StartList;
import org.vaadin.firitin.appframework.MenuItem;
import org.vaadin.firitin.components.grid.VGrid;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Route("table")
@MenuItem(title = "Taulukko", order = MenuItem.END - 1)
public class TableView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final CompetitionRepository competitionRepository;
    private final TulospalveluService tulospalveluService;
    private final DnsService dnsService;
    private final UserSession userSession;

    private String password;
    private String competitionId;
    private List<RunnerRow> allRunners;
    private ListDataProvider<RunnerRow> dataProvider;
    private VGrid<RunnerRow> grid;
    private SharedNumberSignal changeSignal;
    private java.util.function.Consumer<String> startListListener;

    private String nameFilter = "";
    private String numberFilter = "";
    private boolean showStarted = true;
    private boolean showNotStarted = true;
    private Set<String> selectedStartPlaces = Set.of();

    public TableView(CompetitionRepository competitionRepository,
                     TulospalveluService tulospalveluService,
                     DnsService dnsService,
                     UserSession userSession) {
        this.competitionRepository = competitionRepository;
        this.tulospalveluService = tulospalveluService;
        this.dnsService = dnsService;
        this.userSession = userSession;
        setSizeFull();

        setCompetition(userSession.getPassword());
    }

    public void setCompetition(String password) {
        if (password == null) {
            return;
        }
        this.password = password;
        this.competitionId = competitionRepository.findById(password)
                .map(c -> c.getCompetitionId())
                .orElse(password);
        removeAll();
        buildView();
    }

    private void buildView() {
        add(new H1("DNS - " + competitionId));

        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return;
        }

        changeSignal = dnsService.getChangeSignal(password);
        allRunners = parseRunners(startList);

        var allStartPlaces = new TreeSet<String>();
        allRunners.stream().map(RunnerRow::startPlace)
                .filter(s -> !s.isEmpty())
                .forEach(allStartPlaces::add);

        // Filters
        var nameField = new TextField("Nimihaku");
        nameField.setPlaceholder("Nimi...");
        nameField.setClearButtonVisible(true);
        nameField.setValueChangeMode(ValueChangeMode.LAZY);
        nameField.addValueChangeListener(e -> {
            nameFilter = e.getValue() != null ? e.getValue().toLowerCase().trim() : "";
            applyFilters();
        });

        var numberField = new TextField("Numerohaku");
        numberField.setPlaceholder("Numero...");
        numberField.setClearButtonVisible(true);
        numberField.setValueChangeMode(ValueChangeMode.LAZY);
        numberField.addValueChangeListener(e -> {
            numberFilter = e.getValue() != null ? e.getValue().trim() : "";
            applyFilters();
        });

        var startedFilter = new CheckboxGroup<String>();
        startedFilter.setItems("Lähteneet", "Ei lähteneet");
        startedFilter.select("Lähteneet", "Ei lähteneet");
        startedFilter.addValueChangeListener(e -> {
            showStarted = e.getValue().contains("Lähteneet");
            showNotStarted = e.getValue().contains("Ei lähteneet");
            applyFilters();
        });

        var filterRow = new HorizontalLayout(nameField, numberField, startedFilter);
        filterRow.setAlignItems(Alignment.END);

        if (!allStartPlaces.isEmpty()) {
            var placeFilter = new CheckboxGroup<String>("Lähtöpaikka");
            placeFilter.setItems(allStartPlaces);
            placeFilter.addValueChangeListener(e -> {
                selectedStartPlaces = e.getValue();
                applyFilters();
            });
            filterRow.add(placeFilter);
        }

        add(filterRow);

        // Grid
        grid = new VGrid<>();
        dataProvider = new ListDataProvider<>(allRunners);
        dataProvider.setFilter(this::matchesRunner);
        grid.setDataProvider(dataProvider);

        grid.withRowStyler((runner, style) -> {
            if (runner.started()) {
                style.setBackground("#c8e6c9"); // vihreä
            } else if (runner.startTime() != null && LocalTime.now().isAfter(runner.startTime())) {
                style.setBackground("#ffe0b2"); // oranssi
            } else {
                style.remove("background");
            }
        });

        var bibCol = grid.addColumn(RunnerRow::bib).setHeader("Nro").setSortable(true);
        grid.addColumn(RunnerRow::name).setHeader("Nimi").setSortable(true);
        grid.addColumn(RunnerRow::className).setHeader("Sarja").setSortable(true);
        grid.addColumn(r -> r.startTime() != null ? r.startTime().format(TIME_FMT) : "")
                .setHeader("Lähtöaika").setSortable(true)
                .setComparator((a, b) -> {
                    if (a.startTime() == null) return 1;
                    if (b.startTime() == null) return -1;
                    return a.startTime().compareTo(b.startTime());
                });
        grid.addColumn(RunnerRow::startPlace).setHeader("Lähtöpaikka").setSortable(true);
        grid.addColumn(r -> r.started() ? "Kyllä" : "")
                .setHeader("Lähtenyt").setSortable(true);
        grid.addColumn(RunnerRow::club).setHeader("Seura").setSortable(true);

        grid.sort(List.of(new GridSortOrder<>(bibCol, com.vaadin.flow.data.provider.SortDirection.ASCENDING)));
        grid.setSizeFull();
        addAndExpand(grid);
    }

    private void applyFilters() {
        dataProvider.refreshAll();
    }

    private boolean matchesRunner(RunnerRow r) {
        if (r.started() && !showStarted) return false;
        if (!r.started() && !showNotStarted) return false;
        if (!selectedStartPlaces.isEmpty() && !r.startPlace().isEmpty()
                && !selectedStartPlaces.contains(r.startPlace())) return false;
        if (!nameFilter.isEmpty() && !r.name().toLowerCase().contains(nameFilter)) return false;
        if (!numberFilter.isEmpty() && !String.valueOf(r.bib()).contains(numberFilter)) return false;
        return true;
    }

    private void syncFromSignal() {
        Set<Integer> startedBibs = dnsService.getStartedBibs(password);
        boolean changed = false;
        for (int i = 0; i < allRunners.size(); i++) {
            var r = allRunners.get(i);
            boolean shouldBeStarted = startedBibs.contains(r.bib());
            if (r.started() != shouldBeStarted) {
                allRunners.set(i, r.withStarted(shouldBeStarted));
                changed = true;
            }
        }
        if (changed) {
            dataProvider.refreshAll();
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (changeSignal != null) {
            ElementEffect.effect(getElement(), () -> {
                changeSignal.get();
                getUI().ifPresent(ui -> ui.access(this::syncFromSignal));
            });
        }
        startListListener = updatedCompetitionId -> {
            if (updatedCompetitionId.equals(competitionId)) {
                getUI().ifPresent(ui -> ui.access(() -> setCompetition(password)));
            }
        };
        tulospalveluService.addStartListUpdateListener(startListListener);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (startListListener != null) {
            tulospalveluService.removeStartListUpdateListener(startListListener);
            startListListener = null;
        }
    }

    private List<RunnerRow> parseRunners(StartList startList) {
        Set<Integer> startedBibs = dnsService.getStartedBibs(password);
        var runners = new ArrayList<RunnerRow>();

        for (var classStart : startList.getClassStart()) {
            String className = classStart.getClazz() != null
                    ? classStart.getClazz().getName() : "";
            String startPlace = "";
            if (!classStart.getStartName().isEmpty()) {
                startPlace = classStart.getStartName().getFirst().getValue();
            }
            if (startPlace == null) startPlace = "";

            for (var personStart : classStart.getPersonStart()) {
                String name = "?";
                if (personStart.getPerson() != null && personStart.getPerson().getName() != null) {
                    var n = personStart.getPerson().getName();
                    name = n.getGiven() + " " + n.getFamily();
                }
                String club = "";
                if (personStart.getOrganisation() != null) {
                    club = personStart.getOrganisation().getShortName() != null
                            ? personStart.getOrganisation().getShortName()
                            : personStart.getOrganisation().getName();
                }
                if (club == null) club = "";

                for (var raceStart : personStart.getStart()) {
                    int bib = parseBib(raceStart.getBibNumber());
                    LocalTime time = toLocalTime(raceStart.getStartTime());
                    boolean started = bib > 0 && startedBibs.contains(bib);
                    runners.add(new RunnerRow(bib, name, className, time, startPlace, club, started));
                }
            }
        }
        return runners;
    }

    record RunnerRow(int bib, String name, String className, LocalTime startTime,
                     String startPlace, String club, boolean started) {
        RunnerRow withStarted(boolean started) {
            return new RunnerRow(bib, name, className, startTime, startPlace, club, started);
        }
    }

    private static LocalTime toLocalTime(XMLGregorianCalendar cal) {
        if (cal == null) return null;
        return LocalTime.of(cal.getHour(), cal.getMinute(), cal.getSecond());
    }

    private static int parseBib(String bib) {
        if (bib == null || bib.isBlank()) return 0;
        try { return Integer.parseInt(bib.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
