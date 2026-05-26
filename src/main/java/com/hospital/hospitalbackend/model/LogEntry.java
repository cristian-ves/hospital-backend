package com.hospital.hospitalbackend.model;

public record LogEntry(String level, String message, long timestamp) {
    public static LogEntry system(String msg) { return new LogEntry("SYSTEM", msg, System.currentTimeMillis()); }
    public static LogEntry info(String msg)   { return new LogEntry("INFO",   msg, System.currentTimeMillis()); }
    public static LogEntry warn(String msg)   { return new LogEntry("WARN",   msg, System.currentTimeMillis()); }
    public static LogEntry wait(String msg)   { return new LogEntry("WAIT",   msg, System.currentTimeMillis()); }
}