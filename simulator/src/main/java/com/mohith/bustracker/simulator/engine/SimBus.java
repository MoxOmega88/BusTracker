package com.mohith.bustracker.simulator.engine;

import com.mohith.bustracker.common.VehiclePosition;
import com.mohith.bustracker.simulator.gtfs.GtfsModel.Shape;
import com.mohith.bustracker.simulator.gtfs.GtfsModel.Trip;
import java.util.Random;

public class SimBus {
    private final Trip trip;
    private final Shape shape;
    private final double[] t;   // scheduled offset (s) at each stop
    private final double[] d;   // distance along shape (m) at each stop
    private final Random rnd;

    private double progressSec = 0;      // position on the schedule timeline
    private double rate;                 // >1 = running ahead of schedule
    private double lastDist = 0;
    private double delayRemainingSec;    // wall-clock wait before departing

    public SimBus(Trip trip, Shape shape, double startDelayWallSec, Random rnd) {
        this.trip = trip;
        this.shape = shape;
        this.rnd = rnd;
        this.delayRemainingSec = startDelayWallSec;
        int n = trip.stopTimes().size();
        t = new double[n];
        d = new double[n];
        double maxD = 0;
        for (int i = 0; i < n; i++) {
            var st = trip.stopTimes().get(i);
            t[i] = st.scheduledSec() - trip.startSec();
            maxD = Math.max(maxD, st.distAlongShapeM());   // keep distances non-decreasing
            d[i] = maxD;
        }
        this.rate = 0.8 + rnd.nextDouble() * 0.4;
    }

    public String vehicleId() { return "BUS-" + trip.id(); }

    /** Advances the bus; returns null while it is still waiting to depart. */
    public VehiclePosition tick(double wallDtSec, double simDtSec, long simNowMs) {
        if (delayRemainingSec > 0) {
            delayRemainingSec -= wallDtSec;
            return null;
        }
        rate = Math.min(1.5, Math.max(0.5, rate + rnd.nextGaussian() * 0.03));
        progressSec += simDtSec * rate;
        if (progressSec >= t[t.length - 1]) {   // finished the trip: loop for a continuous demo
            progressSec = 0;
            lastDist = 0;
        }
        double dist = distAt(progressSec);
        double speed = Math.max(0, (dist - lastDist) / simDtSec);
        lastDist = dist;
        double[] ll = RouteGeometry.pointAt(shape, dist);
        return new VehiclePosition(vehicleId(), trip.id(), trip.routeId(), trip.routeName(),
                trip.directionId(), ll[0], ll[1], speed, dist, simNowMs);
    }

    private double distAt(double p) {
        int i = 0;
        while (i < t.length - 2 && p > t[i + 1]) i++;
        double dt = t[i + 1] - t[i];
        double f = dt <= 0 ? 1 : Math.min(1, Math.max(0, (p - t[i]) / dt));
        return d[i] + (d[i + 1] - d[i]) * f;
    }
}