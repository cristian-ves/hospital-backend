package com.hospital.hospitalbackend.dto;

public record ResourceStatusDTO(
        int operatingRooms,
        int surgeons,
        int generalDoctors,
        int nurses,
        int ventilators,
        int monitors,
        int emergencyRooms
) {}