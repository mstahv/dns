package com.example.dns.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.timepicker.TimePicker;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.Layout;
import org.vaadin.firitin.appframework.MainLayout;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;

@Layout
public class TopLayout extends MainLayout {

    private CheckboxGroup<String> startPlaceSelect;
    private CheckboxGroup<String> startedFilter;
    private TextField nameField;
    private TextField numberField;
    private DnsView currentDnsView;

    @Override
    protected Object getDrawerHeader() {
        return "DNS";
    }

    @Override
    protected void addDrawerContent() {
        super.addDrawerContent();
    }

    void initFilters(DnsView dnsView) {
        this.currentDnsView = dnsView;

        var footer = getDrawerFooter();
        footer.getStyle().setMaxWidth("20em");
        footer.removeAll();

        var timePicker = new TimePicker("Siirry aikaan");
        timePicker.setStep(Duration.ofMinutes(1));
        timePicker.setValue(LocalTime.now());

        var scrollButton = new Button("Siirry", e -> {
            LocalTime time = timePicker.getValue();
            if (time != null && currentDnsView != null) {
                currentDnsView.scrollToTime(time);
            }
        });

        var scrollRow = new HorizontalLayout(timePicker, scrollButton);
        scrollRow.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.END);
        scrollRow.setWidthFull();
        footer.add(scrollRow, new Hr());

        nameField = new TextField("Nimihaku");
        nameField.setPlaceholder("Nimi...");
        nameField.setClearButtonVisible(true);
        nameField.setValueChangeMode(ValueChangeMode.LAZY);
        nameField.setWidthFull();
        nameField.addValueChangeListener(e -> applyFilters());

        numberField = new TextField("Numerohaku");
        numberField.setPlaceholder("Numero...");
        numberField.setClearButtonVisible(true);
        numberField.setValueChangeMode(ValueChangeMode.LAZY);
        numberField.setWidthFull();
        numberField.addValueChangeListener(e -> applyFilters());

        startedFilter = new CheckboxGroup<>("Näytä");
        startedFilter.setItems("Lähteneet", "Ei lähteneet");
        startedFilter.select("Lähteneet", "Ei lähteneet");
        startedFilter.addValueChangeListener(e -> applyFilters());

        footer.add(nameField, numberField, startedFilter);

        Set<String> places = dnsView.getAllStartPlaces();
        if (!places.isEmpty()) {
            startPlaceSelect = new CheckboxGroup<>("Lähtöpaikka");
            startPlaceSelect.setItems(places);
            startPlaceSelect.addValueChangeListener(e -> applyFilters());
            footer.add(startPlaceSelect);
        }

        var clearButton = new Button("Poista suodattimet", e -> clearFilters());
        clearButton.addThemeVariants(ButtonVariant.TERTIARY);
        clearButton.setWidthFull();
        footer.add(clearButton);

        footer.add(new Hr());

        footer.add(new Button("Vaihda kisaa", e ->
                getUI().ifPresent(ui -> ui.navigate(MainView.class))));
    }

    private void clearFilters() {
        if (nameField != null) {
            nameField.clear();
        }
        if (numberField != null) {
            numberField.clear();
        }
        if (startPlaceSelect != null) {
            startPlaceSelect.clear();
        }
        if (startedFilter != null) {
            startedFilter.select("Lähteneet", "Ei lähteneet");
        }
        applyFilters();
    }

    private void applyFilters() {
        if (currentDnsView == null) {
            return;
        }
        Set<String> places = startPlaceSelect != null ? startPlaceSelect.getValue() : Set.of();
        String name = nameField != null ? nameField.getValue() : "";
        String number = numberField != null ? numberField.getValue() : "";
        boolean showStarted = startedFilter == null || startedFilter.getValue().contains("Lähteneet");
        boolean showNotStarted = startedFilter == null || startedFilter.getValue().contains("Ei lähteneet");
        currentDnsView.applyFilters(places, name, number, showStarted, showNotStarted);
    }
}
