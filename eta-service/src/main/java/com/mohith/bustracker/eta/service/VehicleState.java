package com.mohith.bustracker.eta.service;

import java.util.HashMap;
import java.util.Map;

/** Per-bus state. Only touched by the thread that owns the bus's partition. */
class VehicleState {
    record Prediction(double stopDistM, long schedArrivalMs, long corrArrivalMs) {}

    boolean initialised;
    double lastDist;
    double lastSched;
    long lastTs;
    double pace = 1.0;
    final Map<Integer, Prediction> pending = new HashMap<>();

    void reset() {
        initialised = false;
        lastDist = 0;
        lastSched = 0;
        lastTs = 0;
        pace = 1.0;
        pending.clear();
    }
}