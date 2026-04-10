package com.example.dns.ui;

import com.example.dns.domain.ApprovedMachine;
import com.example.dns.domain.ApprovedMachineRepository;
import com.example.dns.domain.MachineReading;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.time.format.DateTimeFormatter;

@Route("machine-readings")
@PageTitle("Koneluenta")
public class MachineReadingView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ApprovedMachineRepository approvedMachineRepository;
    private final MachineReadingRepository machineReadingRepository;

    private String competitionId;
    private Grid<ApprovedMachine> machineGrid;
    private Grid<MachineReading> readingGrid;

    public MachineReadingView(ApprovedMachineRepository approvedMachineRepository,
                              MachineReadingRepository machineReadingRepository,
                              UserSession userSession) {
        this.approvedMachineRepository = approvedMachineRepository;
        this.machineReadingRepository = machineReadingRepository;
        setWidthFull();

        this.competitionId = userSession.getCompetitionId();
        if (competitionId != null) {
            buildView();
        }
    }

    public void setCompetition(String competitionId) {
        this.competitionId = competitionId;
        removeAll();
        buildView();
    }

    private void buildView() {
        buildMachineSection();
        buildReadingSection();
    }

    private void buildMachineSection() {
        add(new H2("Koneet"));

        machineGrid = new Grid<>(ApprovedMachine.class, false);
        machineGrid.addColumn(ApprovedMachine::getMachineId).setHeader("Kone-ID");
        machineGrid.addColumn(ApprovedMachine::getMachineName).setHeader("Nimi");
        machineGrid.addComponentColumn(this::createApproveToggle).setHeader("Hyväksytty");
        machineGrid.addComponentColumn(this::createDeleteButton).setHeader("");
        machineGrid.setWidthFull();
        refreshMachines();

        var refreshButton = new Button("Päivitä", VaadinIcon.REFRESH.create(),
                e -> { refreshMachines(); refreshReadings(); });

        add(machineGrid, refreshButton);
    }

    private Checkbox createApproveToggle(ApprovedMachine machine) {
        var checkbox = new Checkbox(machine.isApproved());
        checkbox.addValueChangeListener(e -> {
            machine.setApproved(e.getValue());
            approvedMachineRepository.save(machine);
        });
        return checkbox;
    }

    private Button createDeleteButton(ApprovedMachine machine) {
        return new Button(VaadinIcon.TRASH.create(), e -> {
            approvedMachineRepository.delete(machine);
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
        readingGrid.addColumn(MachineReading::getMachineName).setHeader("Kone");
        readingGrid.addColumn(MachineReading::getBib).setHeader("Nro");
        readingGrid.addColumn(MachineReading::getCc).setHeader("Emit");
        readingGrid.addColumn(r -> r.isFound() ? "Kyllä" : "Ei").setHeader("Löytyi");
        readingGrid.setWidthFull();
        refreshReadings();

        add(readingGrid);
    }

    private void refreshMachines() {
        machineGrid.setItems(approvedMachineRepository.findByCompetitionId(competitionId));
    }

    private void refreshReadings() {
        readingGrid.setItems(machineReadingRepository
                .findTop100ByCompetitionIdOrderByReadAtDesc(competitionId));
    }
}
