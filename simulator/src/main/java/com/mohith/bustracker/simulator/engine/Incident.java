package com.mohith.bustracker.simulator.engine;

/** A stretch of one route (as fractions of its length) where buses are slowed or stopped for a time window. */
public record Incident(String id, String type, String shapeId,
                       double fromFrac, double toFrac, double speedFactor,
                       double startSimSec, double endSimSec) {
    public boolean activeAt(double simSec) {
        return simSec >= startSimSec && simSec < endSimSec;
    }
}