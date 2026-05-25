package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.model.Patient;
import org.springframework.stereotype.Service;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;

@Service
public class SimulationService {

    private final ResourceManager resourceManager;
    private final ExecutorService threadPool;
    private final PriorityBlockingQueue<Patient> waitingRoom = new PriorityBlockingQueue<>();

    public SimulationService(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        // Limit threads to 20 to prevent system overload during high patient arrival spikes.
        this.threadPool = Executors.newFixedThreadPool(20);
        startDispatcher();
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
        waitingRoom.add(patient);
    }

    private void runPatientProcess(Patient patient) {
        try {
            resourceManager.acquireResources(patient.getTriageLevel());
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            resourceManager.releaseResources(patient.getTriageLevel());
        }
    }
}