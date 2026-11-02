package com.mohith.bustracker.common;

import java.util.List;

public record EtaUpdate(
        String vehicleId,
        String tripId,
        String routeId,
        String routeName,
        int directionId,
        double lat,
        double lon,
        double distAlongM,
        double speedKmh,
        String status,
        boolean delayed,
        double pace,
        long timestamp,
        List<StopEta> upcoming) {}