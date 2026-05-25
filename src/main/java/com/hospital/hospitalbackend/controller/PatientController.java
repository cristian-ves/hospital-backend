package com.hospital.hospitalbackend.controller;

import com.hospital.hospitalbackend.model.Patient;
import com.hospital.hospitalbackend.service.SimulationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients")
@CrossOrigin(origins = "*")
public class PatientController {

    private final SimulationService simulationService;

    public PatientController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * Endpoint to admit a new patient into the hospital system.
     */
    @PostMapping
    public void admitPatient(@RequestBody Patient patient) {
        simulationService.processPatient(patient);
    }
}