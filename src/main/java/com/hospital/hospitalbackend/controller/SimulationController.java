package com.hospital.hospitalbackend.controller;

import com.hospital.hospitalbackend.service.DeadlockManager;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulation")
@CrossOrigin(origins = "*")
public class SimulationController {

    private final DeadlockManager deadlockManager;

    public SimulationController(DeadlockManager deadlockManager) {
        this.deadlockManager = deadlockManager;
    }

    @PostMapping("/deadlock")
    public void triggerDeadlock() {
        deadlockManager.triggerSimulation();
    }

    @PostMapping("/resolve")
    public void resolveDeadlock(@RequestParam String releasePatientId) {
        deadlockManager.resolve(releasePatientId);
    }
}