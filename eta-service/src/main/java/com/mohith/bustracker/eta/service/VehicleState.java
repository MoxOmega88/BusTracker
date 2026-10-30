package com.mohith.bustracker.eta.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-bus state. Written by the consumer thread that owns the bus's partition; read by the ghost scanner. */
class VehicleState {
    record Prediction(double stopDistM, long schedArrivalMs, long corrArrivalMs) {}

    volatile boolean initialised;
    volatile long lastTs;
    volatile double lastLat;
    volatile double lastLon;
    volatile String routeName = "";

    double lastDist;
    double lastSched;
    double pace = 1.0;

    int slowCount;
    int recoverCount;
    boolean delayed;
    final AtomicBoolean ghostAlerted = new AtomicBoolean(false);

    final Map<Integer, Prediction> pending = new HashMap<>();

    void reset() {
        initialised = false;
        lastDist = 0;
        lastSched = 0;
        lastTs = 0;
        pace = 1.0;
        slowCount = 0;
        recoverCount = 0;
        delayed = false;
        pending.clear();
    }
}