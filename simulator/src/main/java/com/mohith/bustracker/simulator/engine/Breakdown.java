package com.mohith.bustracker.simulator.engine;

/** One bus stops moving and stops reporting for a time window. */
public record Breakdown(String id, String tripId, double startSimSec, double endSimSec) {
    public boolean activeAt(double simSec) {
        return simSec >= startSimSec && simSec < endSimSec;
    }
}