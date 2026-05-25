package com.hospital.hospitalbackend.service;

import com.hospital.hospitalbackend.model.Patient;
import org.springframework.stereotype.Service;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class SimulationService {

    private final ResourceManager resourceManager;
    private final ExecutorService threadPool;

    public SimulationService(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        // Limit threads to 20 to prevent system overload during high patient arrival spikes.
        this.threadPool = Executors.newFixedThreadPool(20);
    }

    public void processPatient(Patient patient) {
        threadPool.submit(() -> {
            try {
                resourceManager.acquireResources(patient.getTriageLevel());

                // Simulate medical intervention duration.
                try {
                    Thread.sleep(10000);
                } finally {
                    // Ensure resources are reclaimed even if a medical process fails, preventing permanent deadlock or resource starvation.
                    resourceManager.releaseResources(patient.getTriageLevel());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}