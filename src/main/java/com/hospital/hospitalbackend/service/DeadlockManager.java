package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.dto.DeadlockStatusDTO;
import com.hospital.hospitalbackend.dto.DeadlockedPatientDTO;
import com.hospital.hospitalbackend.model.LogEntry;
import com.hospital.hospitalbackend.model.Patient;
import com.hospital.hospitalbackend.model.TriageLevel;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DeadlockManager {

    private final ResourceManager resourceManager;
    private final NotificationService notificationService;

    // Tracks active simulation threads by patient ID string
    private final Map<String, Thread> simulationThreads = new ConcurrentHashMap<>();

    private final Map<String, String> patientHolding = new ConcurrentHashMap<>();
    private final Map<String, String> patientWaiting = new ConcurrentHashMap<>();
    private final Map<String, Patient> deadlockedPatientCache = new ConcurrentHashMap<>();

    private boolean deadlockActive = false;

    public DeadlockManager(ResourceManager resourceManager, NotificationService notificationService) {
        this.resourceManager = resourceManager;
        this.notificationService = notificationService;
        startDeadlockDetector();
    }

    public void triggerSimulation() {
        if (deadlockActive) return;

        notificationService.sendLog(LogEntry.warn("[WARN] INITIATING DEADLOCK SIMULATION..."));

        // Completely drain Operating Rooms and Monitors to force immediate blocking
        resourceManager.getOperatingRooms().drainPermits();
        resourceManager.getMonitors().drainPermits();

        // Feed 1 permit back to establish the crossed hold
        resourceManager.getOperatingRooms().release(1);
        resourceManager.getMonitors().release(1);

        // Initialize two distinct patients to lock keys
        Patient patientA = Patient.builder()
                .id(UUID.randomUUID())
                .name("Simulated Critical Patient")
                .triageLevel(TriageLevel.CRITICAL)
                .build();

        Patient patientB = Patient.builder()
                .id(UUID.randomUUID())
                .name("Simulated Emergency Patient")
                .triageLevel(TriageLevel.EMERGENCY)
                .build();

        deadlockedPatientCache.put(patientA.getId().toString(), patientA);
        deadlockedPatientCache.put(patientB.getId().toString(), patientB);

        // Thread A: Acquires Operating Room -> Tries to acquire Monitor
        Thread threadA = new Thread(() -> {
            try {
                resourceManager.getOperatingRooms().acquire(1);
                patientHolding.put(patientA.getId().toString(), "Operating Room");
                notificationService.sendLog(LogEntry.info("[HOLD] Patient A holds Operating Room."));

                Thread.sleep(1500); // Allow thread B to grab its mutual target

                notificationService.sendLog(LogEntry.wait("[REQUEST] Patient A requesting Monitor..."));
                patientWaiting.put(patientA.getId().toString(), "Monitor");

                resourceManager.getMonitors().acquire(1); // Stays here permanently

                // If it passes (resolution phase)
                patientWaiting.remove(patientA.getId().toString());
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                handleInterruption(patientA, "Operating Room");
            } finally {
                cleanUpPatient(patientA.getId().toString(), "CRITICAL");
            }
        });

        // Thread B: Inverted path. Acquires Monitor -> Tries to acquire Operating Room
        Thread threadB = new Thread(() -> {
            try {
                resourceManager.getMonitors().acquire(1);
                patientHolding.put(patientB.getId().toString(), "Monitor");
                notificationService.sendLog(LogEntry.info("[HOLD] Patient B holds Monitor."));

                Thread.sleep(1500);

                notificationService.sendLog(LogEntry.wait("[REQUEST] Patient B requesting Operating Room..."));
                patientWaiting.put(patientB.getId().toString(), "Operating Room");

                resourceManager.getOperatingRooms().acquire(1); // Stays here permanently

                // If it passes (resolution phase)
                patientWaiting.remove(patientB.getId().toString());
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                handleInterruption(patientB, "Monitor");
            } finally {
                cleanUpPatient(patientB.getId().toString(), "EMERGENCY");
            }
        });

        simulationThreads.put(patientA.getId().toString(), threadA);
        simulationThreads.put(patientB.getId().toString(), threadB);

        threadA.start();
        threadB.start();
    }

    private void handleInterruption(Patient patient, String heldResource) {
        notificationService.sendLog(LogEntry.warn("[RESOLVE] Interrupted thread for " + patient.getName()));
        if ("Operating Room".equals(heldResource)) {
            resourceManager.getOperatingRooms().release(1);
        } else {
            resourceManager.getMonitors().release(1);
        }
    }

    private void cleanUpPatient(String patientId, String initialTriage) {
        patientHolding.remove(patientId);
        patientWaiting.remove(patientId);
        simulationThreads.remove(patientId);
        deadlockedPatientCache.remove(patientId);
        resourceManager.broadcastCurrentState();
    }

    public void resolve(String releasePatientId) {
        Thread target = simulationThreads.get(releasePatientId);
        if (target != null && target.isAlive()) {
            target.interrupt(); // Break the semaphore
            deadlockActive = false;

            // Restore remaining standard capacities to normal operating values
            resourceManager.getOperatingRooms().release(3 - resourceManager.getOperatingRooms().availablePermits());
            resourceManager.getMonitors().release(8 - resourceManager.getMonitors().availablePermits());

            notificationService.sendUpdate("deadlock", new DeadlockStatusDTO(false, Collections.emptyList()));
            notificationService.sendLog(LogEntry.system("[SUCCESS] Deadlock condition resolved successfully."));
        }
    }

    private void startDeadlockDetector() {
        Thread detector = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(4000);
                    if (!patientWaiting.isEmpty() && !deadlockActive) {
                        // Check structural condition: A waits for what B holds AND B waits for what A holds
                        List<DeadlockedPatientDTO> victims = new ArrayList<>();

                        for (String pId : patientWaiting.keySet()) {
                            Patient p = deadlockedPatientCache.get(pId);
                            if (p != null) {
                                victims.add(new DeadlockedPatientDTO(
                                        pId, p.getName(), p.getTriageLevel().name(),
                                        patientHolding.getOrDefault(pId, "None"),
                                        patientWaiting.get(pId)
                                ));
                            }
                        }

                        if (victims.size() >= 2) {
                            deadlockActive = true;
                            notificationService.sendLog(LogEntry.warn(" [CRITICAL] Circular Wait Detected! System Deadlocked."));
                            notificationService.sendUpdate("deadlock", new DeadlockStatusDTO(true, victims));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        detector.setDaemon(true);
        detector.start();
    }
}