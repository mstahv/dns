package com.example.dns.ui;

import com.example.dns.domain.MachineReading;
import com.example.dns.domain.MachineReadingRepository;
import com.example.dns.service.UserSession;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.vaadin.firitin.appframework.MenuItem;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Route("unknown-readings")
@PageTitle("Tuntemattomat")
@MenuItem(icon = VaadinIcon.QUESTION_CIRCLE)
public class UnknownReadingsView extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final MachineReadingRepository machineReadingRepository;
    private final String password;

    public UnknownReadingsView(MachineReadingRepository machineReadingRepository,
                               UserSession userSession) {
        this.machineReadingRepository = machineReadingRepository;
        this.password = userSession.getPassword();
        setWidthFull();

        if (password != null) {
            buildView();
        }
    }

    private void buildView() {
        var grid = new Grid<>(MachineReading.class, false);
        grid.addColumn(r -> r.getMachine().getMachineName()).setHeader("Lukija");
        grid.addColumn(r -> r.getReadAt() != null ? TIME_FMT.format(r.getReadAt()) : "")
                .setHeader("Aika");
        grid.addColumn(MachineReading::getCc).setHeader("Emit");
        grid.setWidthFull();
        grid.setItems(machineReadingRepository.findByPasswordAndFoundFalseOrderByReadAtDesc(password));
        add(grid);
    }
}
