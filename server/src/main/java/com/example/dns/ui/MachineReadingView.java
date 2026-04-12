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

        machineGrid = new Grid<>(CompetitionMachine.class, false);
        machineGrid.addColumn(cm -> cm.getMachine().getMachineId()).setHeader("Kone-ID");
        machineGrid.addColumn(cm -> cm.getMachine().getMachineName()).setHeader("Nimi");
        machineGrid.addComponentColumn(cm -> createStatusCell(cm.getMachine())).setHeader("Tila");
        machineGrid.addComponentColumn(cm -> new ServerActions(cm.getMachine())).setHeader("Toiminnot");
        machineGrid.addComponentColumn(cm -> new RenameButton(cm.getMachine())).setHeader("");
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

        String label = date.isEmpty() ? "Online (" + hash + ")" : "Online (" + date + ")";
        var link = new Anchor(GITHUB_COMMIT_URL + hash, label);
        link.setTarget("_blank");
        return link;
    }

    private class ServerActions extends HorizontalLayout {
        ServerActions(Machine machine) {
            setSpacing(false);
            if (webSocketHandler.isOnline(machine)) {
                add(new UpdateButton(machine), new LogButton(machine));
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

    private class LogButton extends VButton {
        LogButton(Machine machine) {
            setIcon(VaadinIcon.FILE_TEXT_O.create());
            addThemeVariants(ButtonVariant.TERTIARY);
            setTooltipText("Näytä koneen lokit");
            addClickListener(e -> {
                setEnabled(false);
                webSocketHandler.requestLogs(machine).thenAccept(logContent ->
                    getUI().ifPresent(ui -> ui.access(() -> {
                        setEnabled(true);
                        showLogDialog(machine, logContent);
                    }))
                );
            });
        }
    }

    private void showLogDialog(Machine machine, String logContent) {
        var dialog = new Dialog();
        dialog.setHeaderTitle("Lokit: " + machine.getMachineName());
        dialog.setWidth("80vw");
        dialog.setHeight("70vh");

        var pre = new Pre(logContent);
        pre.getStyle()
                .setOverflow(com.vaadin.flow.dom.Style.Overflow.AUTO)
                .setFontSize("var(--lumo-font-size-s)");
        pre.setWidthFull();
        pre.setHeight("100%");

        dialog.add(pre);
        dialog.getFooter().add(new Button("Sulje", ev -> dialog.close()));
        dialog.open();
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
