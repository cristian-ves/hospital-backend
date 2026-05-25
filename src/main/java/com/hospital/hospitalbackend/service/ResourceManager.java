package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.dto.ResourceStatusDTO;
import com.hospital.hospitalbackend.model.TriageLevel;
import org.springframework.stereotype.Service;
import java.util.concurrent.Semaphore;

@Service
public class ResourceManager {

    private final NotificationService notificationService;

    // Global Resource Pools
    private final Semaphore operatingRooms = new Semaphore(3);
    private final Semaphore surgeons = new Semaphore(4);
    private final Semaphore generalDoctors = new Semaphore(8);
    private final Semaphore nurses = new Semaphore(10);
    private final Semaphore ventilators = new Semaphore(5);
    private final Semaphore monitors = new Semaphore(8);
    private final Semaphore emergencyRooms = new Semaphore(10); // Represents standard rooms

    public ResourceManager(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void acquireResources(TriageLevel level) throws InterruptedException {
        switch (level) {
            case CRITICAL -> {
                operatingRooms.acquire(1);
                surgeons.acquire(1);
                nurses.acquire(2);
                ventilators.acquire(1);
                monitors.acquire(1);
            }
            case EMERGENCY -> {
                emergencyRooms.acquire(1);
                generalDoctors.acquire(1);
                nurses.acquire(1);
                monitors.acquire(1);
            }
            case URGENT, LESS_URGENT, NON_URGENT -> {
                emergencyRooms.acquire(1);
                generalDoctors.acquire(1);
            }
        }

        // Notify the dashboard
        notificationService.sendUpdate("resource-status", getResourceState());

    }

    public void releaseResources(TriageLevel level) {
        switch (level) {
            case CRITICAL -> {
                operatingRooms.release(1);
                surgeons.release(1);
                nurses.release(2);
                ventilators.release(1);
                monitors.release(1);
            }
            case EMERGENCY -> {
                emergencyRooms.release(1);
                generalDoctors.release(1);
                nurses.release(1);
                monitors.release(1);
            }
            case URGENT, LESS_URGENT, NON_URGENT -> {
                emergencyRooms.release(1);
                generalDoctors.release(1);
            }
        }

        // Notify the dashboard
        notificationService.sendUpdate("resource-status", getResourceState());
    }

    // Helper to get current counts for the UI
    private ResourceStatusDTO getResourceState() {
        return new ResourceStatusDTO(
                operatingRooms.availablePermits(),
                surgeons.availablePermits(),
                generalDoctors.availablePermits(),
                nurses.availablePermits(),
                ventilators.availablePermits(),
                monitors.availablePermits(),
                emergencyRooms.availablePermits()
        );
    }
}