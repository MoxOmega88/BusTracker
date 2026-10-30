package com.mohith.bustracker.simulator.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class IncidentPlan {
    private final List<Incident> zones;
    private final List<Breakdown> breakdowns;

    public IncidentPlan(@Value("${bustracker.sim.incidents-enabled:true}") boolean enabled) {
        if (enabled) {
            // times are simulated seconds since the simulator started (10x speed: 600 sim s = 60 s on the wall clock)
            zones = List.of(
                    new Incident("SLOWDOWN-171", "SLOWDOWN", "171 DOWN", 0.25, 0.45, 0.25, 600, 6000),
                    new Incident("BLOCK-K2", "ROAD_BLOCK", "K-2 MRBS-BEL UP", 0.20, 0.21, 0.0, 700, 3000));
            breakdowns = List.of(
                    new Breakdown("BREAKDOWN-3212", "3212", 1500, 2700));
        } else {
            zones = List.of();
            breakdowns = List.of();
        }
    }

    /** 1.0 = normal; lower values slow the bus; 0.0 stops it. */
    public double speedFactor(String shapeId, double distM, double shapeLenM, double simSec) {
        double frac = shapeLenM > 0 ? distM / shapeLenM : 0;
        double factor = 1.0;
        for (Incident z : zones) {
            if (z.shapeId().equals(shapeId) && z.activeAt(simSec)
                    && frac >= z.fromFrac() && frac <= z.toFrac()) {
                factor = Math.min(factor, z.speedFactor());
            }
        }
        return factor;
    }

    public boolean isBrokenDown(String tripId, double simSec) {
        for (Breakdown b : breakdowns) {
            if (b.tripId().equals(tripId) && b.activeAt(simSec)) return true;
        }
        return false;
    }

    public Set<String> activeIds(double simSec) {
        Set<String> ids = new HashSet<>();
        zones.stream().filter(z -> z.activeAt(simSec)).forEach(z -> ids.add(z.id()));
        breakdowns.stream().filter(b -> b.activeAt(simSec)).forEach(b -> ids.add(b.id()));
        return ids;
    }
}