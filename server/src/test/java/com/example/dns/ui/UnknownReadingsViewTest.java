package com.example.dns.ui;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.CompetitionMachine;
import com.example.dns.domain.CompetitionMachineRepository;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.domain.Machine;
import com.example.dns.domain.MachineReading;
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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UnknownReadingsViewTest {

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
        competition.setCompetitionId("2026_viking");
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

    @Test
    void nayttaaTuntemattomat_eiLoytyneitaLuentoja() {
        var machine = createMachine("reader-1", "Lukija 1");

        // Tuntematon luenta (found=false)
        var unknown = new MachineReading();
        unknown.setPassword(PASSWORD);
        unknown.setMachine(machine);
        unknown.setCc("12345");
        unknown.setReadAt(Instant.now());
        unknown.setFound(false);
        machineReadingRepository.save(unknown);

        // Tunnistettu luenta (found=true) — ei pitäisi näkyä
        var known = new MachineReading();
        known.setPassword(PASSWORD);
        known.setMachine(machine);
        known.setCc("67890");
        known.setBib(1);
        known.setReadAt(Instant.now());
        known.setFound(true);
        machineReadingRepository.save(known);

        ui.navigate(UnknownReadingsView.class);

        var grid = ui.get(Grid.class).first();
        var items = grid.getGenericDataView().getItems().toList();
        assertEquals(1, items.size(), "Vain tuntemattomat luennat pitäisi näkyä");
        var reading = (MachineReading) items.getFirst();
        assertEquals("12345", reading.getCc(), "Emit-numero pitäisi olla tuntemattoman luennan");
    }

    @Test
    void tyhjaLista_kunKaikkiLuennatTunnistettu() {
        var machine = createMachine("reader-2", "Lukija 2");

        var known = new MachineReading();
        known.setPassword(PASSWORD);
        known.setMachine(machine);
        known.setCc("11111");
        known.setBib(5);
        known.setReadAt(Instant.now());
        known.setFound(true);
        machineReadingRepository.save(known);

        ui.navigate(UnknownReadingsView.class);

        var grid = ui.get(Grid.class).first();
        var items = grid.getGenericDataView().getItems().toList();
        assertEquals(0, items.size(), "Ei pitäisi näkyä yhtään luentaa kun kaikki tunnistettu");
    }

    private Machine createMachine(String machineId, String name) {
        var machine = new Machine();
        machine.setMachineId(machineId);
        machine.setMachineName(name);
        return machineRepository.save(machine);
    }
}
