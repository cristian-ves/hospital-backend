package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.dto.PatientStatusDTO;
import com.hospital.hospitalbackend.model.LogEntry;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
public class NotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void sendUpdate(String destination, Object payload) {
        messagingTemplate.convertAndSend("/topic/" + destination, payload);
    }

    public void sendLog(LogEntry entry) {
        messagingTemplate.convertAndSend("/topic/logs", entry);
    }

    public void sendPatientUpdate(Collection<PatientStatusDTO> patients) {
        messagingTemplate.convertAndSend("/topic/patients", patients);
    }
}