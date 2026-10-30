package com.mohith.bustracker.eta.service;

import com.mohith.bustracker.common.EtaUpdate;
import com.mohith.bustracker.common.StopEta;
import com.mohith.bustracker.common.VehiclePosition;
import com.mohith.bustracker.common.gtfs.GtfsModel.GtfsData;
import com.mohith.bustracker.common.gtfs.GtfsModel.StopTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EtaProcessor {
    private static final double PACE_MIN = 0.3;
    private static final double PACE_MAX = 3.0;
    private static final double MAX_GAP_SEC = 300;

    private final Map<String, TripSchedule> schedules = new HashMap<>();
    private final Map<String, VehicleState> states = new ConcurrentHashMap<>();
    private final AccuracyTracker accuracy;
    private final int lookahead;
    private final double alpha;

    public EtaProcessor(GtfsData data, AccuracyTracker accuracy,
                        @Value("${bustracker.eta.lookahead:5}") int lookahead,
                        @Value("${bustracker.eta.pace-alpha:0.2}") double alpha) {
        this.accuracy = accuracy;
        this.lookahead = lookahead;
        this.alpha = alpha;
        data.trips().values().stream()
                .filter(t -> t.stopTimes().size() >= 2)
                .forEach(t -> schedules.put(t.id(), new TripSchedule(t)));
    }

    /** Returns an ETA update, or null if the event should be ignored. */
    public EtaUpdate process(VehiclePosition p) {
        TripSchedule sched = schedules.get(p.tripId());
        if (sched == null) return null;

        VehicleState s = states.computeIfAbsent(p.vehicleId(), k -> new VehicleState());
        double dist = p.distAlongM();
        double schedNow = sched.timeAtDist(dist);

        // duplicate or out-of-order event: ignore
        if (s.initialised && p.timestamp() <= s.lastTs) return null;

        // simulator loops trips: distance jumped back, so start fresh
        if (s.initialised && dist < s.lastDist - 100) s.reset();

        if (s.initialised) {
            double dtSec = (p.timestamp() - s.lastTs) / 1000.0;
            if (dtSec <= MAX_GAP_SEC) {
                double instPace = (schedNow - s.lastSched) / dtSec;
                s.pace = clamp(alpha * instPace + (1 - alpha) * s.pace);
            }
            resolveArrivals(s, p, dist);
        }

        List<StopEta> upcoming = new ArrayList<>();
        for (StopTime st : sched.trip().stopTimes()) {
            double stopDist = st.distAlongShapeM();
            if (stopDist <= dist) continue;

            double schedRemain = Math.max(0, sched.offsetSec(st) - schedNow);
            double corrRemain = schedRemain / s.pace;
            long schedArrival = p.timestamp() + (long) (schedRemain * 1000);
            long corrArrival = p.timestamp() + (long) (corrRemain * 1000);

            upcoming.add(new StopEta(st.stop().id(), st.stop().name(), st.sequence(),
                    stopDist - dist, schedRemain, corrRemain, corrArrival));
            // remember the first prediction we made for this stop, to score it later
            s.pending.putIfAbsent(st.sequence(),
                    new VehicleState.Prediction(stopDist, schedArrival, corrArrival));

            if (upcoming.size() >= lookahead) break;
        }

        s.lastDist = dist;
        s.lastTs = p.timestamp();
        s.lastSched = schedNow;
        s.initialised = true;

        return new EtaUpdate(p.vehicleId(), p.tripId(), p.routeId(), p.routeName(),
                p.directionId(), p.lat(), p.lon(), dist, s.pace, p.timestamp(), upcoming);
    }

    /** Score earlier predictions for every stop the bus has just passed. */
    private void resolveArrivals(VehicleState s, VehiclePosition p, double dist) {
        Iterator<Map.Entry<Integer, VehicleState.Prediction>> it = s.pending.entrySet().iterator();
        while (it.hasNext()) {
            VehicleState.Prediction pr = it.next().getValue();
            if (pr.stopDistM() > dist) continue;

            double f = dist > s.lastDist
                    ? Math.min(1, Math.max(0, (pr.stopDistM() - s.lastDist) / (dist - s.lastDist)))
                    : 1;
            long actualMs = s.lastTs + (long) (f * (p.timestamp() - s.lastTs));

            accuracy.record(Math.abs(pr.schedArrivalMs() - actualMs) / 1000.0,
                            Math.abs(pr.corrArrivalMs() - actualMs) / 1000.0);
            it.remove();
        }
    }

    private static double clamp(double v) {
        return Math.min(PACE_MAX, Math.max(PACE_MIN, v));
    }
}