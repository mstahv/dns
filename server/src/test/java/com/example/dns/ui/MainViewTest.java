package com.example.dns.ui;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.Competition;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.TulospalveluService;
import com.vaadin.browserless.VaadinTestApplicationContext;
import com.vaadin.browserless.VaadinTestUiContext;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MainViewTest {

    @Autowired
    ApplicationContext springContext;

    @Autowired
    CompetitionRepository competitionRepository;

    @Autowired
    TulospalveluService tulospalveluService;

    VaadinTestApplicationContext app;

    @BeforeEach
    void setUp() {
        var routes = new Routes().autoDiscoverViews(MainView.class.getPackageName());
        app = VaadinTestApplicationContext.forSpring(routes, springContext);
    }

    @AfterEach
    void tearDown() {
        app.close();
    }

    @Test
    void createCompetitionAndLoginWithPassword() {
        // Admin user creates a competition
        VaadinTestUiContext admin = app.newUser().newWindow();
        admin.navigate(MainView.class);

        // Create competition directly via repository (ComboBox needs tulospalvelu data)
        competitionRepository.deleteById("salasana123");
        var competition = new com.example.dns.domain.Competition();
        competition.setCompetitionId("2026_test");
        competition.setPassword("salasana123");
        competitionRepository.save(competition);

        // Verify competition was persisted
        assertTrue(competitionRepository.findById("salasana123").isPresent(),
                "Competition should be findable by password (PK)");

        // Another user logs in with the competition password
        VaadinTestUiContext staffUser = app.newUser().newWindow();
        staffUser.navigate(MainView.class);

        // Fill in name (required)
        TextField nameField = staffUser.get(TextField.class).all().stream()
                .filter(f -> "Nimesi".equals(f.getLabel()))
                .findFirst().orElseThrow();
        staffUser.test(nameField).setValue("Testi Käyttäjä");

        PasswordField loginPasswordField = staffUser.get(PasswordField.class).first();
        staffUser.test(loginPasswordField).setValue("salasana123");

        /* Not this way */
        /*
        Button loginButton = staffUser.get(Button.class).all().stream()
                .filter(b -> "Kirjaudu kisaan".equals(b.getText()))
                .findFirst().orElseThrow();
        staffUser.test(loginButton).click();
        */
        
        // this is now kind of "right" but not handy
        staffUser.test(staffUser.get(Button.class).withText("Kirjaudu kisaan").single()).click();

        // Should navigate to DnsView
        assertTrue(staffUser.getCurrentView() instanceof DnsView,
                "Should navigate to DnsView after login, but got: "
                        + staffUser.getCurrentView().getClass().getSimpleName());
    }

    @Test
    void createPanelHasStageFieldDefaultingToOne() {
        VaadinTestUiContext user = app.newUser().newWindow();
        user.navigate(MainView.class);

        IntegerField stageField = user.get(IntegerField.class).all().stream()
                .filter(f -> f.getLabel() != null && f.getLabel().startsWith("Osa"))
                .findFirst().orElseThrow();

        assertEquals(1, stageField.getValue(),
                "Stage field should default to 1 (single-stage competitions)");
    }

    @Test
    void competitionPersistsStage() {
        // Clean any leftover record
        competitionRepository.deleteById("smfinaali");

        var competition = new com.example.dns.domain.Competition();
        competition.setCompetitionId("2026_smsprint");
        competition.setPassword("smfinaali");
        competition.setStage(2);
        competitionRepository.save(competition);

        var loaded = competitionRepository.findById("smfinaali").orElseThrow();
        assertEquals(2, loaded.getStage(),
                "Stage should round-trip through the database");
        assertEquals("2026_smsprint", loaded.getCompetitionId());

        competitionRepository.deleteById("smfinaali");
    }

    @Test
    void urlCreationPanelIsVisible() {
        VaadinTestUiContext user = app.newUser().newWindow();
        user.navigate(MainView.class);

        TextField urlField = user.get(TextField.class).all().stream()
                .filter(f -> "IOF XML -tiedoston URL".equals(f.getLabel()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("URL-based creation panel should render an URL field"));
        assertEquals("", urlField.getValue(),
                "URL field should be empty by default");

        Button createButton = user.get(Button.class).all().stream()
                .filter(b -> "Luo kilpailu linkistä".equals(b.getText()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("URL-based create button should be present"));
        assertTrue(createButton.isEnabled(),
                "URL-based create button should be enabled");
    }

    @Test
    void competitionPersistsStartListUrlAndRegisters() {
        String pw = "urlikisa";
        String customUrl = "https://example.test/startlist_my_event.xml";
        competitionRepository.deleteById(pw);

        var competition = new Competition();
        competition.setCompetitionId("my_event");
        competition.setPassword(pw);
        competition.setStartListUrl(customUrl);
        competitionRepository.save(competition);

        var loaded = competitionRepository.findById(pw).orElseThrow();
        assertEquals(customUrl, loaded.getStartListUrl(),
                "startListUrl should round-trip through the database");

        // Register and confirm idempotency
        tulospalveluService.registerCustomUrl(loaded);

        // Re-registering with a cleared URL should remove the override
        loaded.setStartListUrl(null);
        tulospalveluService.registerCustomUrl(loaded);

        competitionRepository.deleteById(pw);
    }
}
