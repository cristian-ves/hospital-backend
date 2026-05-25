package com.hospital.hospitalbackend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a patient arriving at the emergency department.
 * Implements Comparable to support priority-based ordering in simulation queues.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Patient implements Comparable<Patient> {
    private UUID id;
    private String name;
    private TriageLevel triageLevel;
    private LocalDateTime arrivalTime;

    /**
     * Logic for PriorityBlockingQueue sorting.
     * Lower triage level values indicate higher priority (Critical = 1).
     */
    @Override
    public int compareTo(Patient other) {
        return Integer.compare(this.triageLevel.getPriority(), other.triageLevel.getPriority());
    }
}