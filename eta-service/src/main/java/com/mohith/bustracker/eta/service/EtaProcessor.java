package com.mohith.bustracker.eta.service;

import com.mohith.bustracker.common.BusAlert;
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
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EtaProcessor {
    private static final double PACE_MIN = 0.3;
    private static final double PACE_MAX = 3.0;
    private static final double MAX_GAP_SEC = 300;

    // incident detection (instantaneous pace = fraction of scheduled speed)
    private static final double SLOW_PACE = 0.4;
    private static final int SLOW_PINGS = 3;
    private static final double RECOVER_PACE = 0.45;
    private static final int RECOVER_PINGS = 2;

    public record Result(EtaUpdate update, List<BusAlert> alerts) {}

    private enum DelayEvent { NONE, STARTED, CLEARED }

    private final Map<String, TripSchedule> schedules = new HashMap<>();
    private final Map<String, VehicleState> states = new ConcurrentHashMap<>();
    private final AtomicLong streamTimeMs = new AtomicLong();
    private final AccuracyTracker accuracy;
    private final int lookahead;
    private final double alpha;
    private final long ghostMs;

    public EtaProcessor(GtfsData data, AccuracyTracker accuracy,
                        @Value("${bustracker.eta.lookahead:5}") int lookahead,
                        @Value("${bustracker.eta.pace-alpha:0.2}") double alpha,
                        @Value("${bustracker.eta.ghost-sec:90}") double ghostSec) {
        this.accuracy = accuracy;
        this.lookahead = lookahead;
        this.alpha = alpha;
        this.ghostMs = (long) (ghostSec * 1000);
        data.trips().values().stream()
                .filter(t -> t.stopTimes().size() >= 2)
                .forEach(t -> schedules.put(t.id(), new TripSchedule(t)));
    }

    /** Returns the ETA update and any alerts, or null if the event should be ignored. */
    public Result process(VehiclePosition p) {
        TripSchedule sched = schedules.get(p.tripId());
        if (sched == null) return null;

        VehicleState s = states.computeIfAbsent(p.vehicleId(), k -> new VehicleState());
        double dist = p.distAlongM();
        double schedNow = sched.timeAtDist(dist);

        // duplicate or out-of-order event: ignore
        if (s.initialised && p.timestamp() <= s.lastTs) return null;

        streamTimeMs.accumulateAndGet(p.timestamp(), Math::max);

        // simulator loops trips: distance jumped back, so start fresh
        if (s.initialised && dist < s.lastDist - 100) s.reset();

        List<BusAlert> alerts = new ArrayList<>();
        if (s.ghostAlerted.compareAndSet(true, false)) {
            alerts.add(new BusAlert("GHOST_RECOVERED", "INFO", p.vehicleId(), p.routeName(),
                    p.routeName() + " (" + p.vehicleId() + ") is reporting again",
                    p.lat(), p.lon(), p.timestamp()));
        }

        DelayEvent delayEvent = DelayEvent.NONE;
        double instPace = Double.NaN;
        if (s.initialised) {
            double dtSec = (p.timestamp() - s.lastTs) / 1000.0;
            if (dtSec <= MAX_GAP_SEC) {
                instPace = (schedNow - s.lastSched) / dtSec;
                s.pace = clamp(alpha * instPace + (1 - alpha) * s.pace);
                delayEvent = updateDelayState(s, instPace);
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
            s.pending.putIfAbsent(st.sequence(),
                    new VehicleState.Prediction(stopDist, schedArrival, corrArrival));

            if (upcoming.size() >= lookahead) break;
        }

        s.lastDist = dist;
        s.lastTs = p.timestamp();
        s.lastSched = schedNow;
        s.lastLat = p.lat();
        s.lastLon = p.lon();
        s.routeName = p.routeName();
        s.initialised = true;

        String nextStop = upcoming.isEmpty() ? "end of route" : upcoming.get(0).stopName();
        if (delayEvent == DelayEvent.STARTED) {
            alerts.add(new BusAlert("DELAY", "WARN", p.vehicleId(), p.routeName(),
                    String.format("%s (%s) slowed to %.0f%% of scheduled speed near %s",
                            p.routeName(), p.vehicleId(), instPace * 100, nextStop),
                    p.lat(), p.lon(), p.timestamp()));
        } else if (delayEvent == DelayEvent.CLEARED) {
            alerts.add(new BusAlert("DELAY_CLEARED", "INFO", p.vehicleId(), p.routeName(),
                    String.format("%s (%s) is moving normally again near %s",
                            p.routeName(), p.vehicleId(), nextStop),
                    p.lat(), p.lon(), p.timestamp()));
        }

        EtaUpdate update = new EtaUpdate(p.vehicleId(), p.tripId(), p.routeId(), p.routeName(),
                p.directionId(), p.lat(), p.lon(), dist, s.pace, p.timestamp(), upcoming);
        return new Result(update, alerts);
    }

    /** Called periodically: buses silent for longer than the threshold (in stream time) are ghosts. */
    public List<BusAlert> detectGhosts() {
        long now = streamTimeMs.get();
        List<BusAlert> out = new ArrayList<>();
        for (Map.Entry<String, VehicleState> e : states.entrySet()) {
            VehicleState s = e.getValue();
            if (!s.initialised) continue;
            long silentMs = now - s.lastTs;
            if (silentMs > ghostMs && s.ghostAlerted.compareAndSet(false, true)) {
                out.add(new BusAlert("GHOST_BUS", "CRITICAL", e.getKey(), s.routeName,
                        String.format("%s (%s) has not reported for %d s - last seen at its previous position",
                                s.routeName, e.getKey(), silentMs / 1000),
                        s.lastLat, s.lastLon, now));
            }
        }
        return out;
    }

    private DelayEvent updateDelayState(VehicleState s, double instPace) {
        if (instPace < SLOW_PACE) {
            s.slowCount++;
            s.recoverCount = 0;
        } else {
            s.slowCount = 0;
            if (instPace >= RECOVER_PACE) s.recoverCount++;
            else s.recoverCount = 0;
        }
        if (!s.delayed && s.slowCount >= SLOW_PINGS) {
            s.delayed = true;
            return DelayEvent.STARTED;
        }
        if (s.delayed && s.recoverCount >= RECOVER_PINGS) {
            s.delayed = false;
            return DelayEvent.CLEARED;
        }
        return DelayEvent.NONE;
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