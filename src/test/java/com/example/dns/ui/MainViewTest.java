package com.example.dns.ui;

import com.example.dns.TestcontainersConfiguration;
import com.example.dns.domain.CompetitionRepository;
import com.vaadin.browserless.VaadinTestApplicationContext;
import com.vaadin.browserless.VaadinTestUiContext;
import com.vaadin.browserless.internal.Routes;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MainViewTest {

    @Autowired
    ApplicationContext springContext;

    @Autowired
    CompetitionRepository competitionRepository;

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

        Button loginButton = staffUser.get(Button.class).all().stream()
                .filter(b -> "Kirjaudu kisaan".equals(b.getText()))
                .findFirst().orElseThrow();
        staffUser.test(loginButton).click();

        // Should navigate to DnsView
        assertTrue(staffUser.getCurrentView() instanceof DnsView,
                "Should navigate to DnsView after login, but got: "
                        + staffUser.getCurrentView().getClass().getSimpleName());
    }
}
