package com.mohith.bustracker.simulator.engine;

import com.mohith.bustracker.common.VehiclePosition;
import com.mohith.bustracker.common.gtfs.GtfsModel.Shape;
import com.mohith.bustracker.common.gtfs.GtfsModel.Trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A bus driven by simple physics: accelerates, brakes for stops and red signals, dwells, obeys incidents. */
public class SimBus {
    public record Params(double cruiseMps, double cruiseSpread, double dwellMinSec, double dwellMaxSec,
                         double redProb, double redMinSec, double redMaxSec) {}

    private enum Mode { DRIVE, DWELL, SIGNAL_WAIT }

    private static final double ACCEL = 0.9;            // m/s^2
    private static final double BRAKE = 1.3;            // m/s^2
    private static final double SIGNAL_DECIDE_M = 70;   // a signal turns red or green when the bus is this close
    private static final double NONE = Double.MAX_VALUE;

    private final Trip trip;
    private final Shape shape;
    private final IncidentPlan plan;
    private final Params prm;
    private final Random rnd;
    private final double[] stopD;   // stop positions along the route (m), non-decreasing
    private final double[] sigD;    // traffic signal positions (m), fixed per route direction
    private final double cruise;    // this bus's own cruising speed (m/s)

    private Mode mode = Mode.DRIVE;
    private double dist, v, timer, traffic = 1.0;
    private int nextStop, nextSig;
    private boolean sigDecided, sigRed;
    private double departDelayWallSec;

    public SimBus(Trip trip, Shape shape, double startDelayWallSec, Random rnd, IncidentPlan plan, Params prm) {
        this.trip = trip;
        this.shape = shape;
        this.rnd = rnd;
        this.plan = plan;
        this.prm = prm;
        this.departDelayWallSec = startDelayWallSec;

        int n = trip.stopTimes().size();
        stopD = new double[n];
        double maxD = 0;
        for (int i = 0; i < n; i++) {
            maxD = Math.max(maxD, trip.stopTimes().get(i).distAlongShapeM());
            stopD[i] = maxD;
        }

        Random sr = new Random(shape.id().hashCode());   // same signals for every bus on a route direction
        List<Double> sigs = new ArrayList<>();
        double pos = 300 + sr.nextDouble() * 400;
        while (pos < shape.lengthM() - 200) {
            if (farFromStops(pos)) sigs.add(pos);
            pos += 500 + sr.nextDouble() * 700;
        }
        sigD = sigs.stream().mapToDouble(Double::doubleValue).toArray();

        cruise = prm.cruiseMps() + (rnd.nextDouble() * 2 - 1) * prm.cruiseSpread();
    }

    public String vehicleId() { return "BUS-" + trip.id(); }

    /** Advances the bus by one reporting interval; returns null while waiting to depart or broken down. */
    public VehiclePosition tick(double wallDtSec, double simDtSec, long simNowMs, double simElapsedSec) {
        if (departDelayWallSec > 0) {
            departDelayWallSec -= wallDtSec;
            return null;
        }
        if (plan.isBrokenDown(trip.id(), simElapsedSec)) {
            v = 0;
            return null;   // not moving, not reporting
        }
        double startDist = dist;
        int steps = Math.max(1, (int) Math.round(simDtSec));
        double dt = simDtSec / steps;
        for (int i = 0; i < steps; i++) step(dt, simElapsedSec);

        double speed = Math.max(0, (dist - startDist) / simDtSec);
        double[] ll = RouteGeometry.pointAt(shape, dist);
        String status = mode == Mode.DWELL ? "STOPPED_AT" : "IN_TRANSIT_TO";
        return new VehiclePosition(vehicleId(), trip.id(), trip.routeId(), trip.routeName(),
                trip.directionId(), ll[0], ll[1], speed, dist, status, simNowMs);
    }

    private void step(double dt, double simSec) {
        // slowly drifting traffic conditions: mean-reverting random walk
        traffic = Math.min(1.05, Math.max(0.6, traffic + (1.0 - traffic) * 0.02 + rnd.nextGaussian() * 0.02));

        if (mode != Mode.DRIVE) {
            timer -= dt;
            if (timer > 0) return;
            if (mode == Mode.DWELL && nextStop >= stopD.length) restartTrip();   // layover over: loop for a continuous demo
            mode = Mode.DRIVE;
            return;
        }
        drive(dt, simSec);
    }

    private void drive(double dt, double simSec) {
        while (nextSig < sigD.length && sigD[nextSig] < dist - 1) {   // signals we drove through on green
            nextSig++;
            sigDecided = false;
            sigRed = false;
        }
        double stopPos = nextStop < stopD.length ? stopD[nextStop] : NONE;
        double sigPos = nextSig < sigD.length ? sigD[nextSig] : NONE;
        if (!sigDecided && sigPos != NONE && sigPos < stopPos && sigPos - dist < SIGNAL_DECIDE_M) {
            sigRed = rnd.nextDouble() < prm.redProb();
            sigDecided = true;
        }
        boolean signalControls = sigDecided && sigRed && sigPos < stopPos;
        double ctrlPos = signalControls ? sigPos : stopPos;

        double factor = plan.speedFactor(shape.id(), dist, shape.lengthM(), simSec);
        double vmax = cruise * traffic * factor;
        double vBrake = ctrlPos == NONE ? vmax : 0.8 + Math.sqrt(2 * BRAKE * Math.max(0, ctrlPos - dist));
        double target = Math.min(vmax, vBrake);

        double v0 = v;
        double dv = target - v;
        v += dv > 0 ? Math.min(dv, ACCEL * dt) : Math.max(dv, -BRAKE * dt);
        double move = 0.5 * (v0 + v) * dt;

        if (ctrlPos != NONE && dist + move >= ctrlPos - 0.3) {   // arrived at a stop or a red signal
            dist = ctrlPos;
            v = 0;
            if (signalControls) {
                mode = Mode.SIGNAL_WAIT;
                timer = uniform(prm.redMinSec(), prm.redMaxSec());
                nextSig++;
                sigDecided = false;
                sigRed = false;
            } else {
                mode = Mode.DWELL;
                timer = uniform(prm.dwellMinSec(), prm.dwellMaxSec());
                nextStop++;
                while (nextStop < stopD.length && stopD[nextStop] - ctrlPos < 1.0) nextStop++;   // co-located stops: one halt
            }
            return;
        }
        dist += move;
    }

    private void restartTrip() {
        dist = 0;
        v = 0;
        nextStop = 0;
        nextSig = 0;
        sigDecided = false;
        sigRed = false;
    }

    private boolean farFromStops(double pos) {
        for (double s : stopD) if (Math.abs(s - pos) < 60) return false;
        return true;
    }

    private double uniform(double a, double b) { return a + rnd.nextDouble() * (b - a); }
}