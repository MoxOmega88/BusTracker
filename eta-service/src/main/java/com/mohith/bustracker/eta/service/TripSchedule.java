package com.mohith.bustracker.eta.service;

import com.mohith.bustracker.common.gtfs.GtfsModel.StopTime;
import com.mohith.bustracker.common.gtfs.GtfsModel.Trip;

/** Timetable of one trip, indexed by stop. */
public class TripSchedule {
    private final Trip trip;
    private final double[] t;   // scheduled offset (s) at each stop
    private final double[] d;   // distance along route (m) at each stop, non-decreasing

    public TripSchedule(Trip trip) {
        this.trip = trip;
        int n = trip.stopTimes().size();
        t = new double[n];
        d = new double[n];
        double maxD = 0;
        for (int i = 0; i < n; i++) {
            StopTime st = trip.stopTimes().get(i);
            t[i] = st.scheduledSec() - trip.startSec();
            maxD = Math.max(maxD, st.distAlongShapeM());
            d[i] = maxD;
        }
    }

    public Trip trip() { return trip; }
    public int size() { return t.length; }
    public double timeAt(int i) { return t[i]; }
    public double distAt(int i) { return d[i]; }

    /** Scheduled time offset (s) at a given distance along the route. */
    public double timeAtDist(double dist) {
        int n = d.length;
        if (dist <= d[0]) return t[0];
        if (dist >= d[n - 1]) return t[n - 1];
        int i = 0;
        while (i < n - 2 && dist > d[i + 1]) i++;
        double dd = d[i + 1] - d[i];
        double f = dd <= 0 ? 1 : (dist - d[i]) / dd;
        return t[i] + (t[i + 1] - t[i]) * f;
    }
}