package com.hospital.hospitalbackend.dto;

public record DeadlockedPatientDTO(
        String id,
        String name,
        String triageLevel,
        String holdingResource,
        String waitingForResource
) {}