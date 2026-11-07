package com.mohith.bustracker.simulator.engine;

/** One bus stops moving and stops reporting for a time window. */
public record Breakdown(String id, String tripId, double startSimSec, double endSimSec) {
    public boolean activeAt(double simSec) {
        return simSec >= startSimSec && simSec < endSimSec;
    }
}

//this is a record class in Java that represents a breakdown event for a bus in a bus tracking simulator. It contains four fields: `id`, `tripId`, `startSimSec`, and `endSimSec`. The `activeAt` method checks if the breakdown is active at a given simulation time (`simSec`). If the current simulation time falls within the start and end times of the breakdown, it returns true; otherwise, it returns false.