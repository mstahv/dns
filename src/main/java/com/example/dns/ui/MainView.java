package com.example.dns.ui;

import com.example.dns.domain.Competition;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.CompetitionInfo;
import com.example.dns.service.TulospalveluService;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.page.WebStorage;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;

@Route(value = "", autoLayout = false)
public class MainView extends VerticalLayout {

    private static final String LS_USER_NAME = "dns-user-name";
    private static final String LS_PASSWORD = "dns-password";

    private final UserSession userSession;
    private final TextField nameField = new TextField("Nimesi");

    public MainView(CompetitionRepository competitionRepository,
                    TulospalveluService tulospalveluService,
                    UserSession userSession) {
        this.userSession = userSession;

        add(new H1("DNS - Did Not Start"));

        nameField.setPlaceholder("Nimi...");
        nameField.setWidthFull();

        WebStorage.getItem(LS_USER_NAME, value -> {
            if (value != null && !value.isBlank()) {
                nameField.setValue(value);
            }
        });

        add(nameField);

        var passwordField = new PasswordField("Kisasalasana");
        WebStorage.getItem(LS_PASSWORD, value -> {
            if (value != null && !value.isBlank()) {
                passwordField.setValue(value);
            }
        });

        add(new LoginPanel(competitionRepository, passwordField));

        add(new H1("Luo uusi kilpailu"));

        add(new CreateCompetitionPanel(competitionRepository, tulospalveluService));
    }

    private void saveUserName() {
        String name = nameField.getValue().trim();
        userSession.setName(name);
        WebStorage.setItem(LS_USER_NAME, name);
    }

    private static void savePassword(String password) {
        WebStorage.setItem(LS_PASSWORD, password);
    }

    private class LoginPanel extends VerticalLayout {

        LoginPanel(CompetitionRepository repo, PasswordField passwordField) {
            var loginButton = new Button("Kirjaudu kisaan", e -> {
                if (nameField.getValue().isBlank()) {
                    Notification.show("Syötä nimesi");
                    return;
                }
                repo.findById(passwordField.getValue()).ifPresentOrElse(
                        competition -> {
                            saveUserName();
                            savePassword(passwordField.getValue());
                            userSession.setCompetitionId(competition.getCompetitionId());
                            getUI().ifPresent(ui -> ui.navigate(DnsView.class)
                                    .ifPresent(view -> view.setCompetition(
                                            competition.getCompetitionId())));
                        },
                        () -> Notification.show("Väärä salasana")
                );
            });
            add(passwordField, loginButton);
        }
    }

    private class CreateCompetitionPanel extends VerticalLayout {

        CreateCompetitionPanel(CompetitionRepository repo,
                               TulospalveluService tulospalveluService) {
            var combo = new ComboBox<CompetitionInfo>("Valitse kilpailu");
            combo.setItemLabelGenerator(CompetitionInfo::eventTitle);
            combo.setItems(tulospalveluService.getOrienteeringEvents());
            combo.setWidthFull();

            var passwordField = new TextField("Keksi kisasalasana");

            var createButton = new Button("Luo kilpailu onlinestä...", e -> {
                if (nameField.getValue().isBlank()) {
                    Notification.show("Syötä nimesi");
                    return;
                }
                CompetitionInfo selected = combo.getValue();
                if (selected == null) {
                    Notification.show("Valitse kilpailu");
                    return;
                }
                String password = passwordField.getValue();
                if (password == null || password.isBlank()) {
                    Notification.show("Syötä salasana");
                    return;
                }

                if (repo.findById(password.trim()).isPresent()) {
                    Notification.show("Salasana on jo käytössä");
                    return;
                }

                var competition = new Competition();
                competition.setCompetitionId(selected.eventId());
                competition.setPassword(password.trim());
                repo.save(competition);

                saveUserName();
                savePassword(password.trim());
                userSession.setCompetitionId(selected.eventId());
                getUI().ifPresent(ui -> ui.navigate(DnsView.class)
                        .ifPresent(view -> view.setCompetition(selected.eventId())));
            });
            add(combo, passwordField, createButton);
        }
    }
}
