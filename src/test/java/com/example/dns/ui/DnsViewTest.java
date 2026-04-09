package com.example.dns.ui;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.DnsService;
import com.example.dns.service.UserSession;
import com.vaadin.browserless.VaadinTestApplicationContext;
import com.vaadin.browserless.VaadinTestUiContext;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DnsViewTest {

    private static final String COMPETITION_ID = "2026_viking";

    @Autowired
    ApplicationContext springContext;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    DnsService dnsService;

    VaadinTestApplicationContext app;
    VaadinTestUiContext ui;

    @BeforeEach
    void setUp() {
        var routes = new Routes().autoDiscoverViews(MainView.class.getPackageName());
        app = VaadinTestApplicationContext.forSpring(routes, springContext);

        // Create competition in DB
        var competition = new com.example.dns.domain.Competition();
        competition.setId(COMPETITION_ID);
        competition.setPassword("test123");
        if (competitionRepository.findById(COMPETITION_ID).isEmpty()) {
            competitionRepository.save(competition);
        }

        ui = app.newUser().newWindow();

        // Set user name in session
        var userSession = springContext.getBean(UserSession.class);
        userSession.setName("Testikäyttäjä");
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private DnsView navigateToDns() {
        return ui.navigate(DnsView.class, COMPETITION_ID);
    }

    @Test
    void viewShowsStartTimeSlots() {
        DnsView view = navigateToDns();

        assertFalse(view.getSlotsByTime().isEmpty(),
                "Should have start time slots loaded from tulospalvelu");
    }

    @Test
    void viewShowsRunnerCards() {
        DnsView view = navigateToDns();

        long totalRunners = view.getSlotsByTime().values().stream()
                .mapToLong(slot -> slot.getRunnerCards().size())
                .sum();

        assertTrue(totalRunners > 0, "Should have runner cards in slots");
    }

    // --- Lazy loading ---

    @Test
    void initiallyOnlyFirstSlotsAreMaterialized() {
        DnsView view = navigateToDns();

        var slots = view.getSlotsByTime().values().stream().toList();
        assertTrue(slots.size() > 10, "Test needs more than 10 time slots");

        long materializedCount = slots.stream()
                .filter(StartTimeSlot::isMaterialized)
                .count();

        assertEquals(10, materializedCount,
                "Only first 10 slots should be materialized initially");

        // Later slots should have no runner cards yet
        assertFalse(slots.getLast().isMaterialized(),
                "Last slot should not be materialized");
        assertTrue(slots.getLast().getRunnerCards().isEmpty(),
                "Non-materialized slot should have no runner cards");
    }

    @Test
    void scrollEventMaterializesMoreSlots() {
        DnsView view = navigateToDns();

        var slots = view.getSlotsByTime().values().stream().toList();
        long initialMaterialized = slots.stream()
                .filter(StartTimeSlot::isMaterialized).count();

        // Simulate scroll to slot index 12 (beyond initial 10)
        view.onVisibleSlotChanged(12);

        long afterScrollMaterialized = slots.stream()
                .filter(StartTimeSlot::isMaterialized).count();

        assertTrue(afterScrollMaterialized > initialMaterialized,
                "Scrolling should materialize more slots");
        assertTrue(slots.get(12).isMaterialized(),
                "Slot at scroll position should be materialized");
    }

    @Test
    void filtersAppliedToNewlyMaterializedSlots() {
        DnsView view = navigateToDns();

        // Mark a runner in first slot as started
        RunnerCard firstCard = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        view.onCardClicked(firstCard);

        // Set filter to show only started
        view.applyFilters(Set.of(), "", "", true, false);

        // Scroll to materialize more slots
        view.onVisibleSlotChanged(12);

        // Newly materialized slots should have filter applied:
        // all non-started cards should be hidden
        var slots = view.getSlotsByTime().values().stream().toList();
        for (int i = 10; i < Math.min(22, slots.size()); i++) {
            var slot = slots.get(i);
            if (slot.isMaterialized()) {
                for (RunnerCard card : slot.getRunnerCards()) {
                    if (!card.isStarted()) {
                        assertFalse(card.isVisible(),
                                "Non-started card in newly materialized slot should be hidden when filter is active");
                    }
                }
            }
        }
    }

    @Test
    void textSearchMaterializesAllSlots() {
        DnsView view = navigateToDns();

        var slots = view.getSlotsByTime().values().stream().toList();
        assertTrue(slots.size() > 10, "Test needs more than 10 slots");

        // Name search should force all slots to materialize
        view.applyFilters(Set.of(), "someNameSearch", "", true, true);

        long materializedCount = slots.stream()
                .filter(StartTimeSlot::isMaterialized).count();

        assertEquals(slots.size(), materializedCount,
                "Text search should materialize all slots");
    }

    @Test
    void scrollToTimeMaterializesTargetSlots() {
        DnsView view = navigateToDns();

        var slots = view.getSlotsByTime().values().stream().toList();
        // Pick a late time that is beyond initial materialization
        var lateSlot = slots.get(slots.size() - 1);

        view.scrollToTime(lateSlot.getStartTime());

        assertTrue(lateSlot.isMaterialized(),
                "scrollToTime should materialize the target slot");
    }

    // --- Filtering ---

    @Test
    void filterByName_hidesNonMatchingCards() {
        DnsView view = navigateToDns();

        // Pick first runner's name
        RunnerCard firstCard = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        String targetName = firstCard.getName();

        view.applyFilters(Set.of(), targetName, "", true, true);

        // The target card should be visible
        assertTrue(firstCard.isVisible(), "Card matching name filter should be visible");

        // At least some other cards should be hidden
        long hiddenCount = view.getSlotsByTime().values().stream()
                .flatMap(slot -> slot.getRunnerCards().stream())
                .filter(card -> !card.isVisible())
                .count();
        assertTrue(hiddenCount > 0, "Some cards should be hidden by name filter");
    }

    @Test
    void filterByNumber_hidesNonMatchingCards() {
        DnsView view = navigateToDns();

        RunnerCard firstCard = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        String bibStr = String.valueOf(firstCard.getBibNumber());

        view.applyFilters(Set.of(), "", bibStr, true, true);

        assertTrue(firstCard.isVisible(), "Card matching number filter should be visible");
    }

    @Test
    void clearFilters_showsAllCards() {
        DnsView view = navigateToDns();

        // Apply a restrictive filter
        view.applyFilters(Set.of(), "xyznonexistent", "", true, true);

        long visibleBefore = view.getSlotsByTime().values().stream()
                .flatMap(slot -> slot.getRunnerCards().stream())
                .filter(RunnerCard::isVisible)
                .count();
        assertEquals(0, visibleBefore, "No cards should match nonsense filter");

        // Clear filters
        view.applyFilters(Set.of(), "", "", true, true);

        long visibleAfter = view.getSlotsByTime().values().stream()
                .flatMap(slot -> slot.getRunnerCards().stream())
                .filter(RunnerCard::isVisible)
                .count();
        assertTrue(visibleAfter > 0, "All cards should be visible after clearing filters");
    }

    @Test
    void filterStartedOnly_hidesNotStartedCards() {
        DnsView view = navigateToDns();

        // Mark one card as started
        RunnerCard card = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        view.onCardClicked(card);
        assertTrue(card.isStarted());

        // Filter: show only started
        view.applyFilters(Set.of(), "", "", true, false);

        assertTrue(card.isVisible(), "Started card should be visible");

        long notStartedVisible = view.getSlotsByTime().values().stream()
                .flatMap(slot -> slot.getRunnerCards().stream())
                .filter(c -> !c.isStarted() && c.isVisible())
                .count();
        assertEquals(0, notStartedVisible, "No non-started cards should be visible");
    }

    @Test
    void filterNotStartedOnly_hidesStartedCards() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        view.onCardClicked(card);

        // Filter: show only not started
        view.applyFilters(Set.of(), "", "", false, true);

        assertFalse(card.isVisible(), "Started card should be hidden");

        long notStartedVisible = view.getSlotsByTime().values().stream()
                .flatMap(slot -> slot.getRunnerCards().stream())
                .filter(c -> !c.isStarted() && c.isVisible())
                .count();
        assertTrue(notStartedVisible > 0, "Non-started cards should be visible");
    }

    // --- Marking started ---

    @Test
    void clickCard_marksAsStarted() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        int bib = card.getBibNumber();

        assertFalse(card.isStarted(), "Card should not be started initially");

        // Simulate click on card element
        card.getElement().executeJs("return true"); // trigger pending
        // Call onCardClicked directly since element click events don't fire in browserless tests
        view.onCardClicked(card);

        assertTrue(card.isStarted(), "Card should be marked as started after click");
        assertTrue(dnsService.isStarted(COMPETITION_ID, bib),
                "DnsService should reflect started state");

        // Verify entry has registeredBy
        var entry = dnsService.getEntry(COMPETITION_ID, bib);
        assertTrue(entry.isPresent(), "DnsEntry should exist");
        assertEquals("Testikäyttäjä", entry.get().getRegisteredBy());
    }

    @Test
    void clickStartedCard_showsConfirmDialog_andUnmarksOnConfirm() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        int bib = card.getBibNumber();

        // First mark as started
        view.onCardClicked(card);
        assertTrue(card.isStarted());

        // Click again - should show confirm dialog
        view.onCardClicked(card);

        // Dialog should be open
        List<ConfirmDialog> dialogs = ui.get(ConfirmDialog.class).all();
        assertFalse(dialogs.isEmpty(), "ConfirmDialog should be shown");

        // Confirm the dialog (click "Peru merkintä")
        ConfirmDialog dialog = dialogs.getFirst();
        ComponentUtil.fireEvent(dialog, new ConfirmDialog.ConfirmEvent(dialog, false));

        assertFalse(card.isStarted(), "Card should be unmarked after confirm");
        assertFalse(dnsService.isStarted(COMPETITION_ID, bib),
                "DnsService should reflect unmarked state");
    }

    @Test
    void clickStartedCard_cancelDialog_keepsStarted() {
        DnsView view = navigateToDns();

        RunnerCard card = view.getSlotsByTime().values().iterator().next()
                .getRunnerCards().getFirst();
        int bib = card.getBibNumber();

        // Mark as started
        view.onCardClicked(card);
        assertTrue(card.isStarted());

        // Click again - shows dialog
        view.onCardClicked(card);

        // Cancel the dialog
        List<ConfirmDialog> dialogs = ui.get(ConfirmDialog.class).all();
        assertFalse(dialogs.isEmpty());
        ComponentUtil.fireEvent(dialogs.getFirst(),
                new ConfirmDialog.CancelEvent(dialogs.getFirst(), false));

        assertTrue(card.isStarted(), "Card should remain started after cancel");
        assertTrue(dnsService.isStarted(COMPETITION_ID, bib),
                "DnsService should still show started");
    }
}
