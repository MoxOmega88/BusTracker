package com.mohith.bustracker.common;

public record BusAlert(
        String type,        // DELAY, DELAY_CLEARED, GHOST_BUS, GHOST_RECOVERED
        String severity,    // INFO, WARN, CRITICAL
        String vehicleId,
        String routeName,
        String message,
        double lat,
        double lon,
        long timestamp) {}