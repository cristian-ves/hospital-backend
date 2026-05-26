package com.hospital.hospitalbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enables a simple memory-based message broker to carry messages back to the client on the "/topic" prefix.
        config.enableSimpleBroker("/topic");
        // Messages from the client must be prefixed with "/app".
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // The endpoint the frontend will use to connect to the WebSocket server.
        registry.addEndpoint("/ws-hospital").setAllowedOriginPatterns("*").withSockJS();
    }
}