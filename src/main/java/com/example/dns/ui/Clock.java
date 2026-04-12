package com.example.dns.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.dom.Element;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A clock component that displays current time (HH:mm:ss) rendered
 * client-side. On every minute change, triggers a server roundtrip
 * and fires registered MinuteChangeListeners.
 */
@Tag("span")
public class Clock extends Component {

    private final List<MinuteChangeListener> listeners = new CopyOnWriteArrayList<>();

    public Clock() {
        getElement().getStyle()
                .set("font-family", "monospace")
                .setFontSize("1.2em")
                .setFontWeight("bold");
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        // Client-side JS: update every second, call server on minute change
        Element el = getElement();
        el.executeJs(
            "const el = this;" +
            "let lastMin = -1;" +
            "function tick() {" +
            "  const now = new Date();" +
            "  const h = String(now.getHours()).padStart(2,'0');" +
            "  const m = String(now.getMinutes()).padStart(2,'0');" +
            "  const s = String(now.getSeconds()).padStart(2,'0');" +
            "  el.textContent = h + ':' + m + ':' + s;" +
            "  if (lastMin >= 0 && m != lastMin) {" +
            "    el.$server.onMinuteChanged();" +
            "  }" +
            "  lastMin = m;" +
            "  const delay = 1000 - new Date().getMilliseconds();" +
            "  setTimeout(tick, delay);" +
            "}" +
            "tick();"
        );
    }

    @ClientCallable
    private void onMinuteChanged() {
        for (var listener : listeners) {
            listener.onMinuteChanged();
        }
    }

    public void addMinuteChangeListener(MinuteChangeListener listener) {
        listeners.add(listener);
    }

    @FunctionalInterface
    public interface MinuteChangeListener extends Serializable {
        void onMinuteChanged();
    }
}
