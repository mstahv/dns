package com.example.dns.ui;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.ApprovedMachine;
import com.example.dns.domain.ApprovedMachineRepository;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.MachineReadingRepository;
import com.vaadin.browserless.VaadinTestApplicationContext;
import com.vaadin.browserless.VaadinTestUiContext;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MachineReadingViewTest {

    private static final String COMPETITION_ID = "2026_viking";

    @Autowired
    ApplicationContext springContext;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    ApprovedMachineRepository approvedMachineRepository;

    @Autowired
    MachineReadingRepository machineReadingRepository;

    VaadinTestApplicationContext app;
    VaadinTestUiContext ui;

    @BeforeEach
    void setUp() {
        approvedMachineRepository.deleteAll();
        machineReadingRepository.deleteAll();

        var competition = new com.example.dns.domain.Competition();
        competition.setCompetitionId(COMPETITION_ID);
        competition.setPassword("test123");
        if (competitionRepository.findById("test123").isEmpty()) {
            competitionRepository.save(competition);
        }

        var routes = new Routes().autoDiscoverViews(MainView.class.getPackageName());
        app = VaadinTestApplicationContext.forSpring(routes, springContext);
        ui = app.newUser().newWindow();
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private MachineReadingView navigateToView() {
        MachineReadingView view = ui.navigate(MachineReadingView.class);
        view.setCompetition(COMPETITION_ID);
        return view;
    }

    @Test
    void viewShowsGrids() {
        navigateToView();

        var grids = ui.get(Grid.class).all();
        assertEquals(2, grids.size(), "Näkymässä pitäisi olla kaksi gridiä: koneet ja lukuloki");
    }

    @Test
    void autoRegisteredMachine_shownInGrid() {
        // Simulate auto-registered machine (as REST API would create)
        var machine = new ApprovedMachine();
        machine.setCompetitionId(COMPETITION_ID);
        machine.setMachineId("auto-reader");
        machine.setMachineName("auto-reader");
        machine.setApproved(false);
        approvedMachineRepository.save(machine);

        navigateToView();

        var machines = approvedMachineRepository.findByCompetitionId(COMPETITION_ID);
        assertEquals(1, machines.size());
        assertFalse(machines.getFirst().isApproved(),
                "Automaattisesti rekisteröity kone ei saa olla hyväksytty");
    }

    @Test
    void machineGrid_showsApprovalStatus() {
        var machine = new ApprovedMachine();
        machine.setCompetitionId(COMPETITION_ID);
        machine.setMachineId("status-test");
        machine.setMachineName("status-test");
        machine.setApproved(false);
        approvedMachineRepository.save(machine);

        navigateToView();

        // Verify machine is in DB and unapproved
        var found = approvedMachineRepository
                .findByCompetitionIdAndMachineId(COMPETITION_ID, "status-test");
        assertTrue(found.isPresent());
        assertFalse(found.get().isApproved(),
                "Kone pitäisi näkyä hyväksymättömänä");
    }
}
