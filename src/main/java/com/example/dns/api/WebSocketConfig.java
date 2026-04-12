package com.example.dns.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MachineReadingWebSocketHandler machineReadingHandler;
    private final DnsWebSocketHandler dnsHandler;

    public WebSocketConfig(MachineReadingWebSocketHandler machineReadingHandler,
                           DnsWebSocketHandler dnsHandler) {
        this.machineReadingHandler = machineReadingHandler;
        this.dnsHandler = dnsHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(machineReadingHandler, "/ws/machine-reading")
                .setAllowedOrigins("*");
        registry.addHandler(dnsHandler, "/ws/started")
                .setAllowedOrigins("*");
    }
}
