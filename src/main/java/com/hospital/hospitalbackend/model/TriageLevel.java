package com.hospital.hospitalbackend.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

@Getter
public enum TriageLevel {
    CRITICAL(1, "Immediate attention"),
    EMERGENCY(2, "Max 10 min wait"),
    URGENT(3, "Max 30 min wait"),
    LESS_URGENT(4, "Max 60 min wait"),
    NON_URGENT(5, "Max 120 min wait");

    private final int priority;
    private final String description;

    TriageLevel(int priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    @JsonCreator
    public static TriageLevel fromPriority(int value) {
        for (TriageLevel level : values()) {
            if (level.priority == value) return level;
        }
        throw new IllegalArgumentException("Unknown triage priority: " + value);
    }
}