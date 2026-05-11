package com.example.dns.ui;

import com.example.dns.domain.Competition;
import com.example.dns.domain.CompetitionRepository;
import com.example.dns.service.CompetitionInfo;
import com.example.dns.service.TulospalveluService;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.page.WebStorage;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import org.vaadin.firitin.appframework.MenuItem;
import org.vaadin.firitin.components.button.DefaultButton;

@Route(value = "", autoLayout = false)
@MenuItem(title = "Kisan valinta", order = MenuItem.END, hidden = true)
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

        add(new H2("Kirjaudu olemassa olevaan kilpailuun"));

        var passwordField = new PasswordField("Kisasalasana");
        WebStorage.getItem(LS_PASSWORD, value -> {
            if (value != null && !value.isBlank()) {
                passwordField.setValue(value);
            }
        });

        add(new LoginPanel(competitionRepository, passwordField));

        add(new H2("Luo uusi kilpailu"));

        add(new CreateCompetitionPanel(competitionRepository, tulospalveluService));

        add(new H2("…tai luo IOF XML -linkillä"));

        add(new CreateFromUrlPanel(competitionRepository, tulospalveluService));
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
            var loginButton = new DefaultButton("Kirjaudu kisaan", e -> {
                if (nameField.getValue().isBlank()) {
                    Notification.show("Syötä nimesi");
                    return;
                }
                repo.findById(passwordField.getValue()).ifPresentOrElse(
                        competition -> {
                            saveUserName();
                            savePassword(passwordField.getValue());
                            userSession.setPassword(competition.getPassword());
                            getUI().ifPresent(ui -> ui.navigate(DnsView.class)
                                    .ifPresent(view -> view.setCompetition(
                                            competition.getPassword())));
                        },
                        () -> Notification.show("Väärä salasana")
                );
            });
            add(passwordField, loginButton);
        }
    }

    private class CreateCompetitionPanel extends VerticalLayout {

        private final ComboBox<CompetitionInfo> combo = new ComboBox<>("Valitse kilpailu");
        private final IntegerField stageField = new StageField();
        private final TextField passwordField = new TextField("Keksi kisasalasana");

        CreateCompetitionPanel(CompetitionRepository repo,
                               TulospalveluService tulospalveluService) {
            combo.setItemLabelGenerator(CompetitionInfo::eventTitle);
            combo.setItems(tulospalveluService.getOrienteeringEvents());
            combo.setWidthFull();

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
                Integer stage = stageField.getValue();
                competition.setStage(stage != null && stage >= 1 ? stage : 1);
                repo.save(competition);

                saveUserName();
                savePassword(password.trim());
                userSession.setPassword(password.trim());
                getUI().ifPresent(ui -> ui.navigate(DnsView.class)
                        .ifPresent(view -> view.setCompetition(password.trim())));
            });
            add(combo, stageField, passwordField, createButton);
        }
    }

    private static class StageField extends IntegerField {
        StageField() {
            super("Osa (esim. 1=karsinta, 2=finaali)");
            setValue(1);
            setMin(1);
            setStepButtonsVisible(true);
            setHelperText("Useimmissa kisoissa 1");
        }
    }

    /**
     * Alternative creation flow: user pastes a direct URL to an IOF XML
     * start list (any host) plus a short identifier used as competitionId.
     * The URL is stored on the Competition and registered with
     * TulospalveluService so the same caching/refresh pipeline works.
     */
    private class CreateFromUrlPanel extends VerticalLayout {

        private final TextField urlField = new UrlField();
        private final TextField idField = new IdField();
        private final TextField passwordField = new TextField("Keksi kisasalasana");

        CreateFromUrlPanel(CompetitionRepository repo,
                           TulospalveluService tulospalveluService) {

            var createButton = new Button("Luo kilpailu linkistä", e -> {
                if (nameField.getValue().isBlank()) {
                    Notification.show("Syötä nimesi");
                    return;
                }
                String url = trimmedOrNull(urlField.getValue());
                if (url == null) {
                    Notification.show("Syötä XML-tiedoston URL");
                    return;
                }
                String id = trimmedOrNull(idField.getValue());
                if (id == null) {
                    Notification.show("Syötä lyhyt tunniste kisalle");
                    return;
                }
                String password = trimmedOrNull(passwordField.getValue());
                if (password == null) {
                    Notification.show("Syötä salasana");
                    return;
                }
                if (repo.findById(password).isPresent()) {
                    Notification.show("Salasana on jo käytössä");
                    return;
                }

                var competition = new Competition();
                competition.setCompetitionId(id);
                competition.setPassword(password);
                competition.setStartListUrl(url);
                repo.save(competition);

                tulospalveluService.registerCustomUrl(competition);

                saveUserName();
                savePassword(password);
                userSession.setPassword(password);
                getUI().ifPresent(ui -> ui.navigate(DnsView.class)
                        .ifPresent(view -> view.setCompetition(password)));
            });
            add(urlField, idField, passwordField, createButton);
        }

        private static String trimmedOrNull(String value) {
            if (value == null) return null;
            String t = value.trim();
            return t.isEmpty() ? null : t;
        }
    }

    private static class UrlField extends TextField {
        UrlField() {
            super("IOF XML -tiedoston URL");
            setWidthFull();
            setPlaceholder("https://example.com/startlist.xml");
        }
    }

    private static class IdField extends TextField {
        IdField() {
            super("Kisan tunniste");
            setHelperText("Lyhyt id (esim. 2026_kuntorastit). Käytetään cachen avaimena.");
        }
    }
}
