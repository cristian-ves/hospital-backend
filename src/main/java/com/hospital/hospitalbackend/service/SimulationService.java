package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.dto.PatientStatusDTO;
import com.hospital.hospitalbackend.model.LogEntry;
import com.hospital.hospitalbackend.model.Patient;
import com.hospital.hospitalbackend.model.TriageLevel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SimulationService {

    private final ResourceManager resourceManager;
    private final NotificationService notificationService;
    private final ExecutorService threadPool;
    private final PriorityBlockingQueue<Patient> waitingRoom = new PriorityBlockingQueue<>();

    private final Map<String, PatientStatusDTO> activePatients = new ConcurrentHashMap<>();
    private final AtomicInteger totalAttended = new AtomicInteger(0);
    private final List<Long> waitTimes = Collections.synchronizedList(new ArrayList<>());


    public SimulationService(ResourceManager resourceManager, NotificationService notificationService) {
        this.resourceManager = resourceManager;
        this.notificationService = notificationService;
        this.threadPool = Executors.newFixedThreadPool(20);
        startDispatcher();
        notificationService.sendLog(LogEntry.system("Engine initialized."));
        notificationService.sendLog(LogEntry.wait("Awaiting incoming requests..."));
    }

    private void startDispatcher() {
        Thread dispatcher = new Thread(() -> {
            while (true) {
                try {
                    // Take blocks if empty
                    Patient patient = waitingRoom.take();
                    threadPool.submit(() -> runPatientProcess(patient));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        dispatcher.setDaemon(true); // Dies when app stops
        dispatcher.start();
    }

    public void processPatient(Patient patient) {
        long admittedAt = System.currentTimeMillis();
        PatientStatusDTO status = new PatientStatusDTO(
                patient.getId().toString(), patient.getName(),
                patient.getTriageLevel().name(), "QUEUED", admittedAt, 0L
        );
        activePatients.put(patient.getId().toString(), status);
        notificationService.sendPatientUpdate(activePatients.values());
        notificationService.sendLog(LogEntry.info(
                "[ADMIT] " + patient.getName() + " — Triage: " + patient.getTriageLevel().name()
        ));
        waitingRoom.add(patient);
    }

    private void runPatientProcess(Patient patient) {
        long queuedAt = activePatients.get(patient.getId().toString()).admittedAt();
        try {
            notificationService.sendLog(LogEntry.wait(
                    "[WAIT] " + patient.getName() + " waiting for resources..."
            ));
            // Blocks here until semaphores are available
            resourceManager.acquireResources(patient.getTriageLevel());

            long startedAt = System.currentTimeMillis();
            long waitMs = System.currentTimeMillis() - queuedAt;
            waitTimes.add(waitMs);

            activePatients.put(patient.getId().toString(), new PatientStatusDTO(
                    patient.getId().toString(), patient.getName(),
                    patient.getTriageLevel().name(), "IN_PROGRESS", queuedAt, startedAt
            ));
            notificationService.sendPatientUpdate(activePatients.values());
            notificationService.sendLog(LogEntry.info(
                    "[START] " + patient.getName() + " — resources acquired (waited " + waitMs/1000 + "s)"
            ));

            Thread.sleep(60_000);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            resourceManager.releaseResources(patient.getTriageLevel());
            totalAttended.incrementAndGet();
            activePatients.remove(patient.getId().toString());
            notificationService.sendPatientUpdate(activePatients.values());
            notificationService.sendLog(LogEntry.system(
                    "[DONE] " + patient.getName() + " — treatment complete"
            ));
            broadcastStats();
        }
    }

    private void broadcastStats() {
        double avgWait = waitTimes.isEmpty() ? 0 :
                waitTimes.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0;
        notificationService.sendUpdate("stats", Map.of(
                "totalAttended", totalAttended.get(),
                "avgWaitSeconds", Math.round(avgWait * 10.0) / 10.0
        ));
    }

    /** Called when a new WebSocket client subscribes — replays current state to catch them up. */
    public void broadcastCurrentState() {
        notificationService.sendPatientUpdate(activePatients.values());
        resourceManager.broadcastCurrentState();
        broadcastStats();
    }

    /** Seeds demo patients so the queue is never empty on a fresh start. */
    @jakarta.annotation.PostConstruct
    public void seedDemoPatients() {
        new Thread(() -> {
            try {
                // Small delay so the WS broker is fully initialized before we broadcast.
                Thread.sleep(1500);
                List.of(
                        Patient.builder().id(UUID.randomUUID()).name("Dave Mustaine")
                                .triageLevel(TriageLevel.CRITICAL).arrivalTime(LocalDateTime.now()).build(),
                        Patient.builder().id(UUID.randomUUID()).name("James Hetfield")
                                .triageLevel(TriageLevel.EMERGENCY).arrivalTime(LocalDateTime.now()).build(),
                        Patient.builder().id(UUID.randomUUID()).name("Kirk Hammett")
                                .triageLevel(TriageLevel.URGENT).arrivalTime(LocalDateTime.now()).build()
                ).forEach(this::processPatient);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}