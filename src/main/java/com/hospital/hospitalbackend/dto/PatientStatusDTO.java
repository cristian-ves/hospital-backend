package com.hospital.hospitalbackend.dto;

public record PatientStatusDTO(
        String patientId, String name, String triageLevel,
        String status,   // "QUEUED" | "IN_PROGRESS" | "COMPLETED"
        long admittedAt, long startedAt // 0
) {}