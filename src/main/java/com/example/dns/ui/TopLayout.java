package com.example.dns.ui;

import com.example.dns.service.UserSession;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Layout;
import org.vaadin.firitin.appframework.MainLayout;

@Layout
public class TopLayout extends MainLayout implements BeforeEnterObserver {

    private final UserSession userSession;

    public TopLayout(UserSession userSession) {
        this.userSession = userSession;
    }

    @Override
    protected Object getDrawerHeader() {
        return "DNS }>";
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getNavigationTarget() != MainView.class && userSession.getPassword() == null) {
            event.forwardTo(MainView.class);
        }
    }
}
