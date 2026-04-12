package com.example.dns.ui;

import com.example.dns.api.MachineReadingWebSocketHandler;
import com.example.dns.domain.CompetitionMachine;
import com.example.dns.domain.CompetitionMachineRepository;
import com.example.dns.domain.Machine;
import com.example.dns.domain.MachineReading;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.domain.MachineRepository;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.vaadin.firitin.appframework.MenuItem;
import org.vaadin.firitin.util.BrowserPrompt;

import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.stream.Collectors;

@Route("machine-readings")
@PageTitle("Koneluenta")
@MenuItem(icon = VaadinIcon.AUTOMATION)
public class MachineReadingView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

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
        var refreshButton = new Button("Päivitä", VaadinIcon.REFRESH.create(),
                e -> refreshAll());
        add(refreshButton);

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
        machineGrid.addColumn(cm -> webSocketHandler.isOnline(cm.getMachine()) ? "Online" : "")
                .setHeader("Tila");
        machineGrid.addComponentColumn(this::createRenameButton).setHeader("");
        machineGrid.addComponentColumn(this::createApproveToggle).setHeader("Hyväksytty");
        machineGrid.addComponentColumn(this::createRemoveButton).setHeader("");
        machineGrid.setWidthFull();
        refreshMachines();

        add(machineGrid);
        add(createAddMachineRow());
    }

    private HorizontalLayout createAddMachineRow() {
        addMachineCombo = new ComboBox<>("Lisää olemassa oleva kone kisaan");
        addMachineCombo.setItemLabelGenerator(m -> m.getMachineName() + " (" + m.getMachineId() + ")");
        addMachineCombo.setWidthFull();

        var addButton = new Button("Lisää", VaadinIcon.PLUS.create(), e -> {
            Machine selected = addMachineCombo.getValue();
            if (selected == null) {
                return;
            }
            if (competitionMachineRepository.findByPasswordAndMachine(password, selected).isPresent()) {
                return;
            }
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

        refreshCombo(addMachineCombo);

        var row = new HorizontalLayout(addMachineCombo, addButton);
        row.setAlignItems(Alignment.END);
        row.setWidthFull();
        return row;
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

    private Button createRenameButton(CompetitionMachine cm) {
        return new Button(VaadinIcon.EDIT.create(), e ->
            BrowserPrompt.promptString("Anna koneelle uusi nimi", cm.getMachine().getMachineName())
                    .thenAccept(newName -> {
                        if (newName != null && !newName.isBlank()) {
                            cm.getMachine().setMachineName(newName.trim());
                            machineRepository.save(cm.getMachine());
                            getUI().ifPresent(ui -> ui.access(this::refreshMachines));
                        }
                    })
        ) {{
            addThemeVariants(ButtonVariant.TERTIARY);
        }};
    }

    private Checkbox createApproveToggle(CompetitionMachine cm) {
        var checkbox = new Checkbox(cm.isApproved());
        checkbox.addValueChangeListener(e -> {
            cm.setApproved(e.getValue());
            competitionMachineRepository.save(cm);
            webSocketHandler.notifyMachineUpdated(cm.getMachine());
        });
        return checkbox;
    }

    private Button createRemoveButton(CompetitionMachine cm) {
        return new Button(VaadinIcon.TRASH.create(), e -> {
            competitionMachineRepository.delete(cm);
            webSocketHandler.notifyMachineUpdated(cm.getMachine());
            refreshMachines();
        }) {{
            addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
        }};
    }

    private void buildReadingSection() {
        add(new H2("Lukuloki"));

        readingGrid = new Grid<>(MachineReading.class, false);
        readingGrid.addColumn(r -> r.getReadAt() != null ? r.getReadAt().format(TIME_FMT) : "")
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
