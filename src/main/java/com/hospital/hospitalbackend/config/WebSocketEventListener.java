package com.hospital.hospitalbackend.config;

import com.hospital.hospitalbackend.service.SimulationService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@Component
public class WebSocketEventListener {

    private final SimulationService simulationService;

    public WebSocketEventListener(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * Fires whenever a client subscribes to any topic.
     * Replays the full current state so late-connecting clients aren't shown a blank dashboard.
     */
    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        simulationService.broadcastCurrentState();
    }
}