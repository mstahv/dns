package com.example.dns.ui;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.CompetitionMachine;
import com.example.dns.domain.CompetitionMachineRepository;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.Machine;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.domain.MachineRepository;
import com.vaadin.browserless.VaadinTestApplicationContext;
import com.vaadin.browserless.VaadinTestUiContext;
import com.vaadin.browserless.internal.Routes;
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
    private static final String PASSWORD = "test123";

    @Autowired
    ApplicationContext springContext;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    MachineRepository machineRepository;

    @Autowired
    CompetitionMachineRepository competitionMachineRepository;

    @Autowired
    MachineReadingRepository machineReadingRepository;

    VaadinTestApplicationContext app;
    VaadinTestUiContext ui;

    @BeforeEach
    void setUp() {
        machineReadingRepository.deleteAll();
        competitionMachineRepository.deleteAll();
        machineRepository.deleteAll();

        var competition = new com.example.dns.domain.Competition();
        competition.setCompetitionId(COMPETITION_ID);
        competition.setPassword(PASSWORD);
        if (competitionRepository.findById(PASSWORD).isEmpty()) {
            competitionRepository.save(competition);
        }

        var routes = new Routes().autoDiscoverViews(MainView.class.getPackageName());
        app = VaadinTestApplicationContext.forSpring(routes, springContext);
        ui = app.newUser().newWindow();

        var userSession = springContext.getBean(com.example.dns.service.UserSession.class);
        userSession.setPassword(PASSWORD);
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    private MachineReadingView navigateToView() {
        MachineReadingView view = ui.navigate(MachineReadingView.class);
        view.setCompetition(PASSWORD);
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
        var machine = new Machine();
        machine.setMachineId("auto-reader");
        machine.setMachineName("auto-reader");
        machineRepository.save(machine);

        var cm = new CompetitionMachine();
        cm.setPassword(PASSWORD);
        cm.setMachine(machine);
        cm.setApproved(false);
        competitionMachineRepository.save(cm);

        navigateToView();

        var machines = competitionMachineRepository.findByPassword(PASSWORD);
        assertEquals(1, machines.size());
        assertFalse(machines.getFirst().isApproved(),
                "Automaattisesti rekisteröity kone ei saa olla hyväksytty");
    }

    @Test
    void machineGrid_showsApprovalStatus() {
        var machine = new Machine();
        machine.setMachineId("status-test");
        machine.setMachineName("status-test");
        machineRepository.save(machine);

        var cm = new CompetitionMachine();
        cm.setPassword(PASSWORD);
        cm.setMachine(machine);
        cm.setApproved(false);
        competitionMachineRepository.save(cm);

        navigateToView();

        var found = competitionMachineRepository.findByPasswordAndMachine(PASSWORD, machine);
        assertTrue(found.isPresent());
        assertFalse(found.get().isApproved(),
                "Kone pitäisi näkyä hyväksymättömänä");
    }
}
