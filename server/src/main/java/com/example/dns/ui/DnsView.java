package com.example.dns.ui;

import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.DnsService;
import com.example.dns.service.MachineReadingService;
import com.example.dns.service.MachineReadingService.ReadingResult;
import com.example.dns.service.StartListLookupService;
import com.example.dns.service.TulospalveluService;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.dom.ElementEffect;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.signals.shared.SharedNumberSignal;
import in.virit.emit.Emit250ReaderButton;
import org.orienteering.datastandard._3.PersonStart;
import org.orienteering.datastandard._3.StartList;
import org.vaadin.firitin.appframework.MenuItem;
import org.vaadin.firitin.components.button.VButton;
import org.vaadin.firitin.components.cssgrid.CssGrid;
import org.vaadin.firitin.components.popover.PopoverButton;
import org.vaadin.firitin.layouts.HorizontalFloatLayout;
import org.vaadin.firitin.util.ScreenWakeLock;
import org.vaadin.firitin.util.WebAudio;

import javax.xml.datatype.XMLGregorianCalendar;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

@Route(value = "dns")
@MenuItem(title = "DNS", order = 0, icon = VaadinIcon.CLOCK)
public class DnsView extends VerticalLayout {

    static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final CompetitionRepository competitionRepository;
    private final TulospalveluService tulospalveluService;
    private final DnsService dnsService;
    private final MachineReadingService machineReadingService;
    private final UserSession userSession;

    private String password;
    private String competitionId;
    private SharedNumberSignal changeSignal;
    private java.util.function.Consumer<String> startListListener;

    // All start times sorted, with their pending runner data
    private List<LocalTime> sortedTimes = List.of();
    private Map<LocalTime, List<PendingRunner>> runnersByTime = Map.of();
    private Set<String> allStartPlaces = Set.of();

    // Current displayed slot
    private int currentIndex = -1;
    private final Map<Integer, RunnerCard> cardsByBib = new HashMap<>();
    private PopoverButton timeButton;
    private VerticalLayout slotContainer;
    private CssGrid runnersGrid;
    private Button autoButton;
    private static final long DEFAULT_OFFSET_MINUTES = 4; // show runners with ~4 min to start
    private boolean autoMode = true;
    private LocalTime lastAutoTime;
    private long autoOffsetMinutes = DEFAULT_OFFSET_MINUTES;

    // Filters
    private String nameFilter = "";
    private String numberFilter = "";
    private boolean showStarted = true;
    private boolean showNotStarted = true;
    private Set<String> selectedStartPlaces = Set.of();
    private PopoverButton searchButton;

    // WebSerial card-reading debounce: ignore repeated reads of the same
    // card unless 5 s passes between readings. The reader hardware can
    // re-emit the same card several times in a row.
    static final long CARD_READ_DEBOUNCE_MS = 5_000;
    private int lastReadCard = Integer.MIN_VALUE;
    private long lastReadAt = 0;
    private LongSupplier clock = System::currentTimeMillis;

    public DnsView(CompetitionRepository competitionRepository,
                   TulospalveluService tulospalveluService, DnsService dnsService,
                   MachineReadingService machineReadingService,
                   UserSession userSession) {
        this.competitionRepository = competitionRepository;
        this.tulospalveluService = tulospalveluService;
        this.dnsService = dnsService;
        this.machineReadingService = machineReadingService;
        this.userSession = userSession;
        setSizeFull();
        getStyle().set("--vaadin-card-title-font-size", "var(--aura-font-size-xl)");

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
        cardsByBib.clear();

        StartList startList = tulospalveluService.getStartList(competitionId);
        if (startList == null) {
            return;
        }

        changeSignal = dnsService.getChangeSignal(password);
        parseStartList(startList);
        buildToolbar();

        slotContainer = new VerticalLayout();
        slotContainer.setPadding(false);
        slotContainer.setWidthFull();
        addAndExpand(slotContainer);

        // Navigate to start time ~4 min ahead (staff monitors this view)
        int next = findNextUpcomingTimeIndex(LocalTime.now().plusMinutes(autoOffsetMinutes));
        if (next >= 0) {
            lastAutoTime = sortedTimes.get(next);
            showSlot(next);
        }
    }

    private void parseStartList(StartList startList) {
        var timeSet = new TreeSet<LocalTime>();
        var byTime = new HashMap<LocalTime, List<PendingRunner>>();
        var places = new TreeSet<String>();

        for (var classStart : startList.getClassStart()) {
            if (StartListLookupService.isIgnoredClass(classStart)) continue;
            String className = classStart.getClazz() != null
                    ? classStart.getClazz().getName() : "";
            String startPlace = "";
            if (!classStart.getStartName().isEmpty()) {
                startPlace = classStart.getStartName().getFirst().getValue();
            }
            if (startPlace != null && !startPlace.isBlank()) {
                places.add(startPlace);
            }
            String sp = startPlace != null ? startPlace : "";

            for (var personStart : classStart.getPersonStart()) {
                for (var raceStart : personStart.getStart()) {
                    LocalTime time = toLocalTime(raceStart.getStartTime());
                    if (time == null) continue;
                    int bib = parseBibNumber(raceStart.getBibNumber());
                    timeSet.add(time);
                    byTime.computeIfAbsent(time, k -> new ArrayList<>())
                            .add(new PendingRunner(personStart, className, bib, sp,
                                    toLocalDateTime(raceStart.getStartTime())));
                }
            }
        }
        sortedTimes = new ArrayList<>(timeSet);
        runnersByTime = byTime;
        allStartPlaces = places;
    }

    private void buildToolbar() {
        var prevButton = new Button(VaadinIcon.ARROW_LEFT.create(), e -> {
            autoMode = false;
            updateAutoButton();
            if (currentIndex > 0) showSlot(currentIndex - 1);
        }) {{ addThemeVariants(ButtonVariant.TERTIARY); }};

        timeButton = new PopoverButton(this::createTimeNavigationPanel) {{
            getStyle()
                    .setFontSize("1.5em")
                    .setFontWeight("bold")
                    .setMinWidth("5em");
            addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.LUMO_TERTIARY_INLINE);
            setIcon(null);
        }};

        var nextButton = new Button(VaadinIcon.ARROW_RIGHT.create(), e -> {
            autoMode = false;
            updateAutoButton();
            if (currentIndex < sortedTimes.size() - 1) showSlot(currentIndex + 1);
        }) {{ addThemeVariants(ButtonVariant.TERTIARY); }};

        autoButton = new VButton(VaadinIcon.CLOCK, e -> {
            autoMode = !autoMode;
            updateAutoButton();
            if (autoMode) {
                // Calculate offset: how far ahead/behind the user has scrolled
                if (currentIndex >= 0 && currentIndex < sortedTimes.size()) {
                    LocalTime currentSlotTime = sortedTimes.get(currentIndex);
                    LocalTime now = LocalTime.now();
                    autoOffsetMinutes = java.time.Duration.between(now, currentSlotTime).toMinutes();
                } else {
                    autoOffsetMinutes = 0;
                }
                // Don't jump — stay on current slot, future minute ticks will apply offset
                lastAutoTime = currentIndex >= 0 ? sortedTimes.get(currentIndex) : null;
            }
        });
        updateAutoButton();

        searchButton = new PopoverButton(this::createSearchPanel) {{
            setIcon(VaadinIcon.SEARCH.create());
            addThemeVariants(ButtonVariant.TERTIARY);
        }};

        var toolbar = new HorizontalLayout(prevButton, timeButton, nextButton, autoButton, searchButton){{
            setSpacing(false);
        }};
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        toolbar.setWidthFull();
        toolbar.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        add(toolbar);
    }

    private void updateAutoButton() {
        if (autoMode) {
            autoButton.addThemeVariants(ButtonVariant.PRIMARY);
        } else {
            autoButton.removeThemeVariants(ButtonVariant.PRIMARY);
        }
    }

    private Component createTimeNavigationPanel() {
        var layout = new VerticalLayout();
        layout.setWidth("250px");
        layout.setPadding(true);

        var nowButton = new Button("Nyt", VaadinIcon.CLOCK.create(), e -> {
            autoMode = false;
            autoOffsetMinutes = DEFAULT_OFFSET_MINUTES;
            updateAutoButton();
            int next = findNextUpcomingTimeIndex(LocalTime.now().plusMinutes(DEFAULT_OFFSET_MINUTES));
            if (next >= 0) showSlot(next);
            timeButton.close();
        });
        nowButton.setWidthFull();
        layout.add(nowButton);

        var timeField = new TextField("Siirry aikaan"){{
            setWidth("6em");
            setPlaceholder("HH:mm");
        }};
        if (currentIndex >= 0 && currentIndex < sortedTimes.size()) {
            timeField.setValue(sortedTimes.get(currentIndex).format(TIME_FORMAT));
        }

        var goButton = new Button("Siirry", e -> {
            String val = timeField.getValue().trim().replace('.', ':');
            try {
                LocalTime target = LocalTime.parse(val);
                autoMode = false;
                updateAutoButton();
                int idx = findNearestTimeIndex(target);
                if (idx >= 0) showSlot(idx);
                timeButton.close();
            } catch (Exception ignored) {
                Notification.show("Virheellinen aika");
            }
        });
        goButton.addThemeVariants(ButtonVariant.PRIMARY);

        var minus10 = new Button("-10 min", e -> {
            if (currentIndex >= 0) {
                LocalTime current = sortedTimes.get(currentIndex);
                LocalTime target = current.minusMinutes(10);
                autoMode = false;
                updateAutoButton();
                int idx = findNearestTimeIndex(target);
                if (idx >= 0) showSlot(idx);
            }
        });

        var plus10 = new Button("+10 min", e -> {
            if (currentIndex >= 0) {
                LocalTime current = sortedTimes.get(currentIndex);
                LocalTime target = current.plusMinutes(10);
                autoMode = false;
                updateAutoButton();
                int idx = findNearestTimeIndex(target);
                if (idx >= 0) showSlot(idx);
            }
        });

        var jumpRow = new HorizontalLayout(minus10, plus10);
        jumpRow.setWidthFull();
        jumpRow.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        var closeButton = new Button("Käytä", e -> timeButton.close());
        closeButton.addThemeVariants(ButtonVariant.TERTIARY);
        closeButton.setWidthFull();

        layout.add(new HorizontalFloatLayout(timeField, goButton), jumpRow, closeButton);
        return layout;
    }

    private Component createSearchPanel() {
        var layout = new VerticalLayout();
        layout.setWidth("300px");
        layout.setPadding(true);

        var nameField = new TextField("Nimihaku");
        nameField.setPlaceholder("Nimi...");
        nameField.setClearButtonVisible(true);
        nameField.setValueChangeMode(ValueChangeMode.LAZY);
        nameField.setWidthFull();
        nameField.setValue(nameFilter);
        nameField.addValueChangeListener(e -> {
            nameFilter = e.getValue() != null ? e.getValue().toLowerCase().trim() : "";
            applyFilters();
        });

        var numberField = new TextField("Numerohaku");
        numberField.setPlaceholder("Numero...");
        numberField.setClearButtonVisible(true);
        numberField.setValueChangeMode(ValueChangeMode.LAZY);
        numberField.setWidthFull();
        numberField.setValue(numberFilter);
        numberField.addValueChangeListener(e -> {
            numberFilter = e.getValue() != null ? e.getValue().trim() : "";
            applyFilters();
        });

        var startedFilter = new CheckboxGroup<String>("Näytä");
        startedFilter.setItems("Lähteneet", "Ei lähteneet");
        if (showStarted) startedFilter.select("Lähteneet");
        if (showNotStarted) startedFilter.select("Ei lähteneet");
        startedFilter.addValueChangeListener(e -> {
            showStarted = e.getValue().contains("Lähteneet");
            showNotStarted = e.getValue().contains("Ei lähteneet");
            applyFilters();
        });

        layout.add(nameField, numberField, startedFilter);

        if (!allStartPlaces.isEmpty()) {
            var placeFilter = new CheckboxGroup<String>("Lähtöpaikka");
            placeFilter.setItems(allStartPlaces);
            if (!selectedStartPlaces.isEmpty()) placeFilter.setValue(selectedStartPlaces);
            placeFilter.addValueChangeListener(e -> {
                selectedStartPlaces = e.getValue();
                applyFilters();
            });
            layout.add(placeFilter);
        }

        var clearButton = new Button("Tyhjennä", e -> {
            nameField.clear();
            numberField.clear();
            startedFilter.select("Lähteneet", "Ei lähteneet");
            nameFilter = "";
            numberFilter = "";
            showStarted = true;
            showNotStarted = true;
            selectedStartPlaces = Set.of();
            applyFilters();
        });
        clearButton.addThemeVariants(ButtonVariant.TERTIARY);
        clearButton.setWidthFull();

        // "Sulje" button to close the popover — users expect a close action
        var closeButton = new Button("Käytä", e -> {
            searchButton.close();
        });
        closeButton.addThemeVariants(ButtonVariant.PRIMARY);
        closeButton.setWidthFull();

        layout.add(clearButton, closeButton);
        return layout;
    }

    private void showSlot(int index) {
        if (index < 0 || index >= sortedTimes.size()) return;
        currentIndex = index;
        cardsByBib.clear();

        LocalTime time = sortedTimes.get(index);
        timeButton.setText(time.format(TIME_FORMAT)
                + " (" + (index + 1) + "/" + sortedTimes.size() + ")");

        slotContainer.removeAll();

        runnersGrid = new CssGrid();
        runnersGrid.setTemplateColumns("repeat(auto-fill, minmax(220px, 1fr))");
        runnersGrid.setGap("8px");
        runnersGrid.setWidthFull();

        Set<Integer> startedBibs = dnsService.getStartedBibs(password);
        List<PendingRunner> runners = runnersByTime.getOrDefault(time, List.of());

        var sorted = new ArrayList<>(runners);
        sorted.sort(Comparator.comparing(p -> formatName(p.personStart())));

        for (var pending : sorted) {
            var card = new RunnerCard(pending.personStart(), pending.className(),
                    pending.bibNumber(), pending.startPlace(), time,
                    pending.startDateTime(), password, dnsService, userSession.getName());
            if (startedBibs.contains(pending.bibNumber())) {
                card.setStarted(true);
            }
            card.addCardClickListener(this::onCardClicked);
            card.setVisible(matchesRunner(card));
            cardsByBib.put(card.getBibNumber(), card);
            runnersGrid.add(card);
        }

        slotContainer.add(runnersGrid);
    }

    private void applyFilters() {
        for (RunnerCard card : cardsByBib.values()) {
            card.setVisible(matchesRunner(card));
        }
    }

    private boolean matchesRunner(RunnerCard card) {
        if (card.isStarted() && !showStarted) return false;
        if (!card.isStarted() && !showNotStarted) return false;
        if (!selectedStartPlaces.isEmpty() && !card.getStartPlace().isEmpty()
                && !selectedStartPlaces.contains(card.getStartPlace())) return false;
        if (!nameFilter.isEmpty() && !card.getName().toLowerCase().contains(nameFilter)) return false;
        if (!numberFilter.isEmpty() && !String.valueOf(card.getBibNumber()).contains(numberFilter)) return false;
        return true;
    }

    /**
     * Handles a card read from the WebSerial Emit reader. Looks up the
     * competitor by control card number, marks them as started, and shows an
     * alert dialog when the runner does not belong to the slot currently
     * displayed (early/late) or when the card is unknown. Unknown cards are
     * also reported to the backend, mirroring the API-based machine flow.
     *
     * The reader can re-emit the same card a number of times in a row; an
     * identical card number is ignored until either a different card is read
     * or {@link #CARD_READ_DEBOUNCE_MS} has elapsed without any reading.
     */
    void handleCardReading(int controlCard) {
        long now = clock.getAsLong();
        if (controlCard == lastReadCard && (now - lastReadAt) < CARD_READ_DEBOUNCE_MS) {
            // Same card seen again within the debounce window: keep the
            // window measured from this latest reading and bail out.
            lastReadAt = now;
            return;
        }
        lastReadCard = controlCard;
        lastReadAt = now;

        ReadingResult result = machineReadingService.processBrowserReading(
                password, controlCard, userSession.getName());

        if (!result.found()) {
            new CardAlertDialog(
                    "Tuntematon kortti",
                    "Emit-numero " + controlCard + " ei löytynyt lähtölistalta. "
                            + "Lukutapahtuma kirjattu palvelimelle.").open();
            return;
        }

        RunnerCard runnerCard = cardsByBib.get(result.bib());
        if (runnerCard != null) {
            runnerCard.setStarted(true);
            WebAudio.get().playOkBeep();
            return;
        }

        String runnerStartTime = result.startTime() == null || result.startTime().isBlank()
                ? "?" : shortenTime(result.startTime());
        String currentSlot = currentIndex >= 0 && currentIndex < sortedTimes.size()
                ? sortedTimes.get(currentIndex).format(TIME_FORMAT) : "?";
        String relation = describeRelativeTiming(result.startTime());

        new CardAlertDialog(
                "Eri lähtöajalla: " + result.name(),
                result.name() + " (nro " + result.bib() + ", lähtöaika " + runnerStartTime + ") "
                        + relation + " näkyvään lähtöaikaan " + currentSlot
                        + ". Merkintä on tehty.").open();
    }

    private String describeRelativeTiming(String runnerStartTimeStr) {
        if (runnerStartTimeStr == null || runnerStartTimeStr.isBlank()
                || currentIndex < 0 || currentIndex >= sortedTimes.size()) {
            return "ei kuulu nykyiseen lähtöaikaan";
        }
        try {
            LocalTime runnerTime = LocalTime.parse(shortenTime(runnerStartTimeStr));
            LocalTime currentTime = sortedTimes.get(currentIndex);
            if (runnerTime.isBefore(currentTime)) return "on liian aikaisin";
            if (runnerTime.isAfter(currentTime)) return "on liian myöhässä";
            return "kuuluu nykyiseen lähtöaikaan";
        } catch (Exception e) {
            return "ei kuulu nykyiseen lähtöaikaan";
        }
    }

    private static String shortenTime(String hhmmss) {
        return hhmmss.length() >= 5 ? hhmmss.substring(0, 5) : hhmmss;
    }

    /**
     * Test hook: override the clock used by the card-reading debounce.
     */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Modal alert dialog that the user must dismiss with a single button.
     */
    private static class CardAlertDialog extends ConfirmDialog {
        CardAlertDialog(String header, String message) {
            setHeader(header);
            setText(message);
            setConfirmText("OK");
            setCancelable(false);
            WebAudio.get().playAlarm();
        }
    }

    void onCardClicked(RunnerCard card) {
        boolean serverStarted = dnsService.isStarted(password, card.getBibNumber());

        if (serverStarted) {
            card.setStarted(true);

            var dialog = new ConfirmDialog(
                    "Peru lähtömerkintä?",
                    card.getName() + " (nro " + card.getBibNumber() + ") on merkitty lähteneeksi. Haluatko perua merkinnän?",
                    "Peru merkintä",
                    e -> {
                        dnsService.unmarkStarted(password, card.getBibNumber());
                        card.setStarted(false);
                    });
            dialog.setCancelable(true);
            dialog.setCancelText("Peruuta");
            dialog.setConfirmButtonTheme("error primary");
            dialog.open();
        } else {
            dnsService.markStarted(password, card.getBibNumber(), userSession.getName());
            card.setStarted(true);
        }
    }

    public void syncCardsFromSignal() {
        for (var entry : cardsByBib.entrySet()) {
            boolean shouldBeStarted = dnsService.isStarted(password, entry.getKey());
            RunnerCard card = entry.getValue();
            if (card.isStarted() != shouldBeStarted) {
                card.setStarted(shouldBeStarted);
            }
        }
    }

    private void checkAutoAdvance() {
        if (!autoMode || sortedTimes.isEmpty()) return;
        LocalTime target = LocalTime.now().plusMinutes(autoOffsetMinutes);
        int next = findNextUpcomingTimeIndex(target);
        if (next >= 0 && next != currentIndex) {
            LocalTime nextTime = sortedTimes.get(next);
            if (!nextTime.equals(lastAutoTime)) {
                lastAutoTime = nextTime;
                showSlot(next);
            }
        }
    }

    /**
     * Finds the next upcoming start time (first time that is >= target).
     * If all times are in the past, returns the last one.
     */
    private int findNextUpcomingTimeIndex(LocalTime target) {
        if (sortedTimes.isEmpty()) return -1;
        for (int i = 0; i < sortedTimes.size(); i++) {
            if (!sortedTimes.get(i).isBefore(target)) {
                return i;
            }
        }
        return sortedTimes.size() - 1;
    }

    /**
     * Finds the nearest past or current time (<= target).
     * Used for manual time navigation.
     */
    private int findNearestTimeIndex(LocalTime target) {
        if (sortedTimes.isEmpty()) return -1;
        int best = 0;
        for (int i = 0; i < sortedTimes.size(); i++) {
            if (!sortedTimes.get(i).isAfter(target)) {
                best = i;
            }
        }
        return best;
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        if (changeSignal != null) {
            ElementEffect.effect(getElement(), () -> {
                changeSignal.get();
                getUI().ifPresent(currentUi -> currentUi.access(this::syncCardsFromSignal));
            });
        }

        // Add clock to navbar and use minute change for auto-advance
        var clock = new Clock();
        clock.addMinuteChangeListener(this::checkAutoAdvance);
        findAncestor(TopLayout.class).addNavbarHelper(clock);

        startListListener = updatedCompetitionId -> {
            if (updatedCompetitionId.equals(competitionId)) {
                getUI().ifPresent(ui -> ui.access(() -> {
                    // Save navigation state before rebuild
                    LocalTime savedSlotTime = (currentIndex >= 0 && currentIndex < sortedTimes.size())
                            ? sortedTimes.get(currentIndex) : null;

                    Notification.show("Lähtölista päivittynyt, näkymä päivitetään...");
                    setCompetition(password);

                    // Restore position so user's time offset is preserved
                    if (savedSlotTime != null && !sortedTimes.isEmpty()) {
                        int idx = findNearestTimeIndex(savedSlotTime);
                        if (idx >= 0) {
                            lastAutoTime = sortedTimes.get(idx);
                            showSlot(idx);
                        }
                    }
                }));
            }
        };
        tulospalveluService.addStartListUpdateListener(startListListener);


        Emit250ReaderButton emit250ReaderButton = new Emit250ReaderButton(() -> {
            Notification.show("Lukija valmis");
        }, card -> handleCardReading(card.ecardNumber()));
        emit250ReaderButton.getContent().addClickListener(event -> {
            // Also request wake lock
            ScreenWakeLock.request();
        });
        emit250ReaderButton.setFilterUsbReaders(true);
        emit250ReaderButton.getContent().setText("");
        emit250ReaderButton.getContent().setIcon(VaadinIcon.AUTOMATION.create());
        findAncestor(TopLayout.class).addNavbarHelper(emit250ReaderButton);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (startListListener != null) {
            tulospalveluService.removeStartListUpdateListener(startListListener);
            startListListener = null;
        }
    }

    // Exposed for tests
    Map<Integer, RunnerCard> getCardsByBib() {
        return cardsByBib;
    }

    List<LocalTime> getSortedTimes() {
        return sortedTimes;
    }

    int getCurrentIndex() {
        return currentIndex;
    }

    private static String formatName(PersonStart ps) {
        if (ps.getPerson() == null || ps.getPerson().getName() == null) return "?";
        var name = ps.getPerson().getName();
        return name.getGiven() + " " + name.getFamily();
    }

    private static LocalTime toLocalTime(XMLGregorianCalendar cal) {
        if (cal == null) return null;
        return LocalTime.of(cal.getHour(), cal.getMinute(), cal.getSecond());
    }

    private static LocalDateTime toLocalDateTime(XMLGregorianCalendar cal) {
        if (cal == null) return null;
        return LocalDateTime.of(cal.getYear(), cal.getMonth(), cal.getDay(),
                cal.getHour(), cal.getMinute(), cal.getSecond());
    }

    private static int parseBibNumber(String bib) {
        if (bib == null || bib.isBlank()) return 0;
        try { return Integer.parseInt(bib.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    record PendingRunner(PersonStart personStart, String className,
                         int bibNumber, String startPlace, LocalDateTime startDateTime) {
    }
}
