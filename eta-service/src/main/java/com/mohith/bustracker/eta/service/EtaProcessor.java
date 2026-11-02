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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class EtaProcessor {
    private static final double PACE_MIN = 0.3;
    private static final double PACE_MAX = 3.0;
    private static final double ARRIVE_EPS_M = 1.0;
    private static final long RESTART_GAP_MS = 600_000;

    // delay detection: absolute speed, ignoring time spent halted at a stop
    private static final double SLOW_MPS = 2.0;       // about 7 km/h
    private static final int SLOW_PINGS = 8;          // 80 simulated seconds in a row
    private static final double RECOVER_MPS = 3.0;
    private static final int RECOVER_PINGS = 3;

    public record Result(EtaUpdate update, List<BusAlert> alerts) {}

    private enum DelayEvent { NONE, STARTED, CLEARED }

    private final Map<String, TripSchedule> schedules = new HashMap<>();
    private final Map<String, VehicleState> states = new ConcurrentHashMap<>();
    private final AtomicLong streamTimeMs = new AtomicLong();
    private final AccuracyTracker accuracy;
    private final int scoreLookahead;
    private final int maxPublished;
    private final double alpha;
    private final long ghostMs;

    public EtaProcessor(GtfsData data, AccuracyTracker accuracy,
                        @Value("${bustracker.eta.score-lookahead:5}") int scoreLookahead,
                        @Value("${bustracker.eta.max-published-stops:40}") int maxPublished,
                        @Value("${bustracker.eta.pace-alpha:0.4}") double alpha,
                        @Value("${bustracker.eta.ghost-sec:90}") double ghostSec) {
        this.accuracy = accuracy;
        this.scoreLookahead = scoreLookahead;
        this.maxPublished = maxPublished;
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
        final long ts = p.timestamp();
        final double dist = p.distAlongM();

        if (s.initialised && ts < s.lastTs - RESTART_GAP_MS) s.reset();   // simulator restarted with an older clock
        if (s.initialised && ts <= s.lastTs) return null;                  // duplicate or out-of-order event
        streamTimeMs.updateAndGet(cur -> ts < cur - RESTART_GAP_MS ? ts : Math.max(cur, ts));
        if (s.initialised && dist < s.lastDist - 100) s.reset();           // trip finished, bus starts again

        List<BusAlert> alerts = new ArrayList<>();
        if (s.ghostAlerted.compareAndSet(true, false)) {
            alerts.add(new BusAlert("GHOST_RECOVERED", "INFO", p.vehicleId(), p.routeName(),
                    p.routeName() + " (" + p.vehicleId() + ") is reporting again",
                    p.lat(), p.lon(), ts));
        }

        int n = sched.size();
        DelayEvent delayEvent = DelayEvent.NONE;
        if (!s.initialised) {
            // first ping of this trip: stops at or behind the bus have unknown arrival times
            s.nextArr = 0;
            while (s.nextArr < n && sched.distAt(s.nextArr) <= dist + ARRIVE_EPS_M) s.nextArr++;
            if (s.nextArr > 0) {
                s.lastArrIdx = s.nextArr - 1;
                s.lastArrTs = ts;
                s.lastArrExact = false;
            }
        } else {
            while (s.nextArr < n && sched.distAt(s.nextArr) <= dist + ARRIVE_EPS_M) {
                double sd = sched.distAt(s.nextArr);
                double frac = dist > s.lastDist
                        ? Math.min(1, Math.max(0, (sd - s.lastDist) / (dist - s.lastDist))) : 1;
                onArrival(s, sched, s.nextArr, s.lastTs + (long) (frac * (ts - s.lastTs)));
                s.nextArr++;
            }
            delayEvent = updateDelayState(s, p);
        }

        // pace: how this bus is running against the timetable, learned from completed segments
        double schedNow = sched.timeAtDist(dist);
        double effPace = s.pace;
        if (s.lastArrIdx >= 0 && s.lastArrIdx + 1 < n) {
            double planned = sched.timeAt(s.lastArrIdx + 1) - sched.timeAt(s.lastArrIdx);
            double elapsed = (ts - s.lastArrTs) / 1000.0;
            if (planned > 0 && elapsed > planned / s.pace) {   // current segment is taking longer than the pace predicts
                effPace = clamp(Math.min(s.pace, planned / elapsed));
            }
        }

        List<StopTime> stops = sched.trip().stopTimes();
        List<StopEta> upcoming = new ArrayList<>();
        int scored = 0;
        for (int i = s.nextArr; i < n && upcoming.size() < maxPublished; i++) {
            double schedRemain = Math.max(0, sched.timeAt(i) - schedNow);
            double corrRemain = schedRemain / effPace;
            StopTime st = stops.get(i);
            upcoming.add(new StopEta(st.stop().id(), st.stop().name(), st.sequence(),
                    r1(sched.distAt(i) - dist), r1(schedRemain), r1(corrRemain),
                    ts + (long) (corrRemain * 1000)));
            if (scored < scoreLookahead) {   // only the next few stops are scored, so errors are comparable
                s.pending.putIfAbsent(i, new VehicleState.Prediction(
                        ts + (long) (schedRemain * 1000), ts + (long) (corrRemain * 1000)));
                scored++;
            }
        }

        s.lastDist = dist;
        s.lastTs = ts;
        s.lastLat = p.lat();
        s.lastLon = p.lon();
        s.routeName = p.routeName();
        s.initialised = true;

        String nextStop = upcoming.isEmpty() ? "the end of the route" : upcoming.get(0).stopName();
        if (delayEvent == DelayEvent.STARTED) {
            alerts.add(new BusAlert("DELAY", "WARN", p.vehicleId(), p.routeName(),
                    String.format("%s (%s) is crawling at %.0f km/h near %s",
                            p.routeName(), p.vehicleId(), p.speedMps() * 3.6, nextStop),
                    p.lat(), p.lon(), ts));
        } else if (delayEvent == DelayEvent.CLEARED) {
            alerts.add(new BusAlert("DELAY_CLEARED", "INFO", p.vehicleId(), p.routeName(),
                    String.format("%s (%s) is moving normally again near %s",
                            p.routeName(), p.vehicleId(), nextStop),
                    p.lat(), p.lon(), ts));
        }

        String status = p.currentStatus() == null ? "IN_TRANSIT_TO" : p.currentStatus();
        EtaUpdate update = new EtaUpdate(p.vehicleId(), p.tripId(), p.routeId(), p.routeName(), p.directionId(),
                r6(p.lat()), r6(p.lon()), r1(dist), r1(p.speedMps() * 3.6), status, s.delayed,
                r3(effPace), ts, upcoming);
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

    /** A stop has just been reached: score the earlier predictions and learn from the finished segment. */
    private void onArrival(VehicleState s, TripSchedule sched, int idx, long arrTs) {
        VehicleState.Prediction pr = s.pending.remove(idx);
        if (pr != null) {
            accuracy.record(Math.abs(pr.schedArrivalMs() - arrTs) / 1000.0,
                            Math.abs(pr.corrArrivalMs() - arrTs) / 1000.0);
        }
        if (s.lastArrIdx >= 0 && s.lastArrExact) {
            double actual = (arrTs - s.lastArrTs) / 1000.0;
            double planned = sched.timeAt(idx) - sched.timeAt(s.lastArrIdx);
            if (actual > 1 && planned > 0) {
                s.pace = alpha * clamp(planned / actual) + (1 - alpha) * s.pace;
            }
        }
        s.lastArrIdx = idx;
        s.lastArrTs = arrTs;
        s.lastArrExact = true;
    }

    private DelayEvent updateDelayState(VehicleState s, VehiclePosition p) {
        if ("STOPPED_AT".equals(p.currentStatus())) return DelayEvent.NONE;   // halting at a stop is normal
        double v = p.speedMps();
        if (v < SLOW_MPS) {
            s.slowCount++;
            s.recoverCount = 0;
        } else {
            s.slowCount = 0;
            s.recoverCount = v >= RECOVER_MPS ? s.recoverCount + 1 : 0;
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

    private static double clamp(double v) { return Math.min(PACE_MAX, Math.max(PACE_MIN, v)); }
    private static double r1(double v) { return Math.round(v * 10) / 10.0; }
    private static double r3(double v) { return Math.round(v * 1000) / 1000.0; }
    private static double r6(double v) { return Math.round(v * 1_000_000) / 1_000_000.0; }
}