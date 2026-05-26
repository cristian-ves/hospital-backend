package com.hospital.hospitalbackend.dto;

import java.util.List;

public record DeadlockStatusDTO(
        boolean isDeadlocked,
        List<DeadlockedPatientDTO> patients
) {}