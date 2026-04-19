package com.example.dns.ui;

import com.example.dns.api.MachineReadingWebSocketHandler;
import com.example.dns.domain.CompetitionMachine;
import com.example.dns.domain.CompetitionMachineRepository;
import com.example.dns.domain.Machine;
import com.example.dns.domain.MachineReading;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.domain.MachineRepository;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.vaadin.firitin.appframework.MenuItem;
import org.vaadin.firitin.components.button.ConfirmButton;
import org.vaadin.firitin.components.button.VButton;
import org.vaadin.firitin.util.BrowserPrompt;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;

@Route("machine-readings")
@PageTitle("Koneluenta")
@MenuItem(icon = VaadinIcon.AUTOMATION)
public class MachineReadingView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final String GITHUB_COMMIT_URL = "https://github.com/mstahv/dns/commit/";

    private final MachineRepository machineRepository;
    private final CompetitionMachineRepository competitionMachineRepository;
    private final MachineReadingRepository machineReadingRepository;
    private final MachineReadingWebSocketHandler webSocketHandler;

    private String password;
    private Grid<CompetitionMachine> machineGrid;
    private Grid<MachineReading> readingGrid;
    private ComboBox<Machine> addMachineCombo;

    public MachineReadingView(MachineRepository machineRepository,
                              CompetitionMachineRepository competitionMachineRepository,
                              MachineReadingRepository machineReadingRepository,
                              MachineReadingWebSocketHandler webSocketHandler,
                              UserSession userSession) {
        this.machineRepository = machineRepository;
        this.competitionMachineRepository = competitionMachineRepository;
        this.machineReadingRepository = machineReadingRepository;
        this.webSocketHandler = webSocketHandler;
        setWidthFull();

        this.password = userSession.getPassword();
        if (password != null) {
            buildView();
        }
    }

    public void setCompetition(String password) {
        this.password = password;
        removeAll();
        buildView();
    }

    private void buildView() {
        add(new Button("Päivitä", VaadinIcon.REFRESH.create(), e -> refreshAll()));
        buildMachineSection();
        buildReadingSection();
    }

    private void refreshAll() {
        refreshMachines();
        refreshReadings();
        refreshCombo(addMachineCombo);
    }

    private void buildMachineSection() {
        add(new H2("Koneet ja niiden yhdistys tähän kisaan"));
        add(new Span("Kone-kisa-yhdistykset poistetaan automaattisesti joka yö klo 04:00."));

        machineGrid = new Grid<>(CompetitionMachine.class, false);
        machineGrid.addColumn(cm -> cm.getMachine().getMachineId()).setHeader("Kone-ID");
        machineGrid.addColumn(cm -> cm.getMachine().getMachineName()).setHeader("Nimi");
        machineGrid.addComponentColumn(cm -> createStatusCell(cm.getMachine())).setHeader("Tila");
        machineGrid.addComponentColumn(cm -> new ServerActions(cm)).setHeader("Toiminnot");
        machineGrid.addComponentColumn(cm -> new ApproveToggle(cm)).setHeader("Hyväksytty");
        machineGrid.addComponentColumn(cm -> new RemoveButton(cm)).setHeader("");
        machineGrid.setWidthFull();
        refreshMachines();

        add(machineGrid);
        add(new AddMachineRow());
    }

    private void refreshCombo(ComboBox<Machine> combo) {
        Set<Long> associatedMachineIds = competitionMachineRepository.findByPassword(password)
                .stream()
                .map(cm -> cm.getMachine().getId())
                .collect(Collectors.toSet());
        var available = machineRepository.findAll().stream()
                .filter(m -> !associatedMachineIds.contains(m.getId()))
                .toList();
        combo.setItems(available);
    }

    /**
     * Parses version string "abc1234 2026-04-12 18:30" into a status cell
     * with a GitHub commit link and commit date.
     */
    private Component createStatusCell(Machine machine) {
        if (!webSocketHandler.isOnline(machine)) return new Span();
        String version = webSocketHandler.getVersion(machine);
        if (version == null) return new Span("Online");

        // Parse "abc1234 2026-04-12 18:30" or just "abc1234"
        String[] parts = version.split(" ", 2);
        String hash = parts[0];
        String date = parts.length > 1 ? parts[1] : "";

        var link = new Anchor(GITHUB_COMMIT_URL + hash, "Online (" + hash + ")");
        link.setTarget("_blank");
        return link;
    }

    private class ServerActions extends HorizontalLayout {
        ServerActions(CompetitionMachine cm) {
            setSpacing(false);
            Machine machine = cm.getMachine();
            add(new RenameButton(machine));
            if (webSocketHandler.isOnline(machine)) {
                add(new UpdateButton(machine), new LogButton(machine),
                        new WifiButton(machine), new ShutdownButton(machine));
            }
        }
    }

    private class UpdateButton extends ConfirmButton {
        UpdateButton(Machine machine) {
            super(VaadinIcon.DOWNLOAD.create(), () -> webSocketHandler.requestUpdate(machine));
            setConfirmationPrompt("Päivityspyyntö");
            setConfirmationDescription("Lähetetäänkö päivityspyyntö koneelle " + machine.getMachineName() + "?");
            setOkText("Lähetä");
            setCancelText("Peruuta");
            addThemeVariants(ButtonVariant.TERTIARY);
            setTooltipText("Lähetä OTA-päivityspyyntö");
        }
    }

    private class ShutdownButton extends ConfirmButton {
        ShutdownButton(Machine machine) {
            super(VaadinIcon.POWER_OFF.create(), () -> webSocketHandler.requestShutdown(machine));
            setConfirmationPrompt("Sammuta laite?");
            setConfirmationDescription("Sammutetaanko " + machine.getMachineName() + "? Laite täytyy käynnistää uudelleen fyysisesti.");
            setOkText("Sammuta");
            setCancelText("Peruuta");
            addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
            setTooltipText("Sammuta laite");
        }
    }

    private class LogButton extends VButton {
        LogButton(Machine machine) {
            setIcon(VaadinIcon.FILE_TEXT_O.create());
            addThemeVariants(ButtonVariant.TERTIARY);
            setTooltipText("Näytä koneen lokit");
            addClickListener(e -> {
                var currentUI = e.getSource().getUI().orElse(null);
                if (currentUI == null) return;
                setEnabled(false);
                webSocketHandler.requestLogs(machine).thenAccept(logContent ->
                    currentUI.access(() -> {
                        setEnabled(true);
                        new LogDialog(machine, logContent).open();
                    })
                );
            });
        }
    }

    private static class LogDialog extends Dialog {
        private static final String READS_LOG_SEPARATOR = "--- reads.log";

        LogDialog(Machine machine, String logContent) {
            setHeaderTitle("Lokit: " + machine.getMachineName());
            setWidth("80vw");
            setHeight("70vh");

            int separatorIdx = logContent.indexOf(READS_LOG_SEPARATOR);
            if (separatorIdx >= 0) {
                String serviceLogs = logContent.substring(0, separatorIdx).stripTrailing();
                String readsLogs = logContent.substring(separatorIdx);

                var tabSheet = new TabSheet();
                tabSheet.setSizeFull();
                tabSheet.add("Palvelun lokit", createLogPre(serviceLogs));
                tabSheet.add("Emit-lukemat", createLogPre(readsLogs));
                add(tabSheet);
            } else {
                add(createLogPre(logContent));
            }

            getFooter().add(new Button("Sulje", ev -> close()));
        }

        private static Pre createLogPre(String content) {
            var pre = new Pre(content);
            pre.getStyle()
                    .setOverflow(com.vaadin.flow.dom.Style.Overflow.AUTO)
                    .setFontSize("var(--lumo-font-size-s)");
            pre.setWidthFull();
            pre.setHeight("100%");
            return pre;
        }
    }

    private class WifiButton extends VButton {
        WifiButton(Machine machine) {
            setIcon(VaadinIcon.CONNECT.create());
            addThemeVariants(ButtonVariant.TERTIARY);
            setTooltipText("WiFi-verkkojen hallinta");
            addClickListener(e -> {
                var currentUI = e.getSource().getUI().orElse(null);
                if (currentUI == null) return;
                setEnabled(false);
                webSocketHandler.requestWifiList(machine).thenAccept(networks ->
                    currentUI.access(() -> {
                        setEnabled(true);
                        new WifiDialog(machine, networks).open();
                    })
                );
            });
        }
    }

    private class WifiDialog extends Dialog {
        private final Machine machine;
        private final VerticalLayout networkList;

        WifiDialog(Machine machine, String networksData) {
            this.machine = machine;
            setHeaderTitle("WiFi-verkot: " + machine.getMachineName());
            setWidth("500px");

            var content = new VerticalLayout();
            content.setPadding(false);

            content.add(new H2("Nykyiset verkot"));
            networkList = new VerticalLayout();
            networkList.setPadding(false);
            networkList.setSpacing(false);
            updateNetworkList(networksData);
            content.add(networkList);

            content.add(new H2("Lisää uusi verkko"));
            content.add(new AddWifiForm());

            add(content);
            getFooter().add(new Button("Sulje", ev -> close()));
        }

        private void updateNetworkList(String networksData) {
            networkList.removeAll();
            if (networksData == null || networksData.isBlank()
                    || networksData.startsWith("(") || networksData.startsWith("Virhe")) {
                networkList.add(new Span(networksData != null ? networksData : "Ei verkkoja"));
                return;
            }
            for (String name : networksData.split("\n")) {
                if (!name.isBlank()) {
                    var span = new Span(name.trim());
                    if (name.contains("[aktiivinen]")) {
                        span.getStyle().setFontWeight(com.vaadin.flow.dom.Style.FontWeight.BOLD);
                    }
                    networkList.add(span);
                }
            }
        }

        private class AddWifiForm extends VerticalLayout {
            AddWifiForm() {
                setPadding(false);
                var ssidField = new TextField("Verkon nimi (SSID)");
                ssidField.setWidthFull();
                var passwordField = new PasswordField("Salasana");
                passwordField.setWidthFull();
                var addBtn = new Button("Lisää verkko", VaadinIcon.PLUS.create(), e -> {
                    String ssid = ssidField.getValue().trim();
                    if (ssid.isEmpty()) return;
                    String psk = passwordField.getValue();
                    e.getSource().setEnabled(false);

                    var currentUI = getUI().orElse(null);
                    if (currentUI == null) return;

                    webSocketHandler.requestAddWifi(machine, ssid, psk).thenAccept(result ->
                        currentUI.access(() -> {
                            e.getSource().setEnabled(true);
                            if ("OK".equals(result)) {
                                Notification.show("Verkko " + ssid + " lisätty",
                                        3000, Notification.Position.MIDDLE)
                                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                                ssidField.clear();
                                passwordField.clear();
                                refreshWifiList(currentUI);
                            } else {
                                Notification.show(result, 5000, Notification.Position.MIDDLE)
                                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
                            }
                        })
                    );
                });
                add(ssidField, passwordField, addBtn);
            }
        }

        private void refreshWifiList(com.vaadin.flow.component.UI ui) {
            webSocketHandler.requestWifiList(machine).thenAccept(networks ->
                ui.access(() -> updateNetworkList(networks))
            );
        }
    }

    private class RenameButton extends VButton {{
        setIcon(VaadinIcon.EDIT.create());
        addThemeVariants(ButtonVariant.TERTIARY);
        setTooltipText("Nimeä uudelleen");
    }
        RenameButton(Machine machine) {
            addClickListener(e ->
                BrowserPrompt.promptString("Anna koneelle uusi nimi", machine.getMachineName())
                    .thenAccept(newName -> {
                        if (newName != null && !newName.isBlank()) {
                            machine.setMachineName(newName.trim());
                            machineRepository.save(machine);
                            getUI().ifPresent(ui -> ui.access(
                                    MachineReadingView.this::refreshMachines));
                        }
                    })
            );
        }
    }

    private class ApproveToggle extends Checkbox {
        ApproveToggle(CompetitionMachine cm) {
            super(cm.isApproved());
            addValueChangeListener(e -> {
                cm.setApproved(e.getValue());
                competitionMachineRepository.save(cm);
                webSocketHandler.notifyMachineUpdated(cm.getMachine());
            });
        }
    }

    private class RemoveButton extends ConfirmButton {
        RemoveButton(CompetitionMachine cm) {
            super(VaadinIcon.TRASH.create(), () -> {
                competitionMachineRepository.delete(cm);
                webSocketHandler.notifyMachineUpdated(cm.getMachine());
                refreshMachines();
            });
            setConfirmationPrompt("Poista kone?");
            setConfirmationDescription("Poistetaanko " + cm.getMachine().getMachineName() + " tästä kisasta?");
            setOkText("Poista");
            setCancelText("Peruuta");
            addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
            setTooltipText("Poista kone kisasta");
        }
    }

    private class AddMachineRow extends HorizontalLayout {
        AddMachineRow() {
            addMachineCombo = new ComboBox<>("Lisää olemassa oleva kone kisaan");
            addMachineCombo.setItemLabelGenerator(m -> m.getMachineName() + " (" + m.getMachineId() + ")");
            addMachineCombo.setWidthFull();
            refreshCombo(addMachineCombo);

            var addButton = new Button("Lisää", VaadinIcon.PLUS.create(), e -> {
                Machine selected = addMachineCombo.getValue();
                if (selected == null) return;
                if (competitionMachineRepository.findByPasswordAndMachine(password, selected).isPresent()) return;
                var cm = new CompetitionMachine();
                cm.setPassword(password);
                cm.setMachine(selected);
                cm.setApproved(false);
                competitionMachineRepository.save(cm);
                webSocketHandler.notifyMachineUpdated(selected);
                addMachineCombo.clear();
                refreshMachines();
                refreshCombo(addMachineCombo);
            });

            add(addMachineCombo, addButton);
            setAlignItems(Alignment.END);
            setWidthFull();
        }
    }

    private void buildReadingSection() {
        add(new H2("Lukuloki"));

        readingGrid = new Grid<>(MachineReading.class, false);
        readingGrid.addColumn(r -> r.getReadAt() != null ? TIME_FMT.format(r.getReadAt()) : "")
                .setHeader("Aika");
        readingGrid.addColumn(r -> r.getMachine().getMachineName()).setHeader("Kone");
        readingGrid.addColumn(MachineReading::getBib).setHeader("Nro");
        readingGrid.addColumn(MachineReading::getCc).setHeader("Emit");
        readingGrid.addColumn(r -> r.isFound() ? "Kyllä" : "Ei").setHeader("Löytyi");
        readingGrid.setWidthFull();
        refreshReadings();

        add(readingGrid);
    }

    private void refreshMachines() {
        machineGrid.setItems(competitionMachineRepository.findByPassword(password));
    }

    private void refreshReadings() {
        readingGrid.setItems(machineReadingRepository
                .findTop100ByPasswordOrderByReadAtDesc(password));
    }
}
