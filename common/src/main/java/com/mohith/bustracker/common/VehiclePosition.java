package com.mohith.bustracker.common;

/** Shaped after GTFS-Realtime VehiclePosition. currentStatus: IN_TRANSIT_TO or STOPPED_AT. */
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
        String currentStatus,
        long timestamp) {}