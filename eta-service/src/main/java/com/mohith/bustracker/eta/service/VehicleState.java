package com.mohith.bustracker.eta.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-bus state. Written by the consumer thread that owns the bus's partition; read by the ghost scanner. */
class VehicleState {
    record Prediction(long schedArrivalMs, long corrArrivalMs) {}

    volatile boolean initialised;
    volatile long lastTs;
    volatile double lastLat;
    volatile double lastLon;
    volatile String routeName = "";

    double lastDist;
    double pace = 1.0;          // smoothed ratio: timetable duration / actual duration of completed segments

    int nextArr;                // index of the next stop the bus has not yet reached
    int lastArrIdx = -1;        // last stop it reached
    long lastArrTs;
    boolean lastArrExact;       // false if the arrival time is only the first time we saw the bus

    int slowCount;
    int recoverCount;
    boolean delayed;
    final AtomicBoolean ghostAlerted = new AtomicBoolean(false);

    final Map<Integer, Prediction> pending = new HashMap<>();   // stop index -> first prediction made for it

    void reset() {
        initialised = false;
        lastDist = 0;
        lastTs = 0;
        pace = 1.0;
        nextArr = 0;
        lastArrIdx = -1;
        lastArrTs = 0;
        lastArrExact = false;
        slowCount = 0;
        recoverCount = 0;
        delayed = false;
        pending.clear();
    }
}