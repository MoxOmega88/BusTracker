package com.mohith.bustracker.common;

public record StopEta(
        String stopId,
        String stopName,
        int sequence,
        double distToStopM,
        double scheduledEtaSec,
        double correctedEtaSec,
        long arrivalTimeMs) {}