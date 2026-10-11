package com.mohith.bustracker.common;

public record VehiclePosition(
        String vehicleId,
        String tripId,
        String routeId,
        String routeName,
        int directionId,
        double lat,
        double lon,
        double speedMps,
        double distAlongM,
        long timestamp) {}