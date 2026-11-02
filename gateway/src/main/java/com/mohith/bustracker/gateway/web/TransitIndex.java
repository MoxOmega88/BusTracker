package com.mohith.bustracker.gateway.web;

import com.mohith.bustracker.common.gtfs.GtfsModel.GtfsData;
import com.mohith.bustracker.common.gtfs.GtfsModel.Shape;
import com.mohith.bustracker.common.gtfs.GtfsModel.StopTime;
import com.mohith.bustracker.common.gtfs.GtfsModel.Trip;
import org.springframework.stereotype.Component;

import java.util.*;

/** Read-only index over the GTFS data: route shapes, stop search and direct-route lookup. */
@Component
public class TransitIndex {
    public record StopDto(String id, String name, double lat, double lon) {}

    public record ShapeDto(String shapeId, String routeName, int directionId, String headsign,
                           List<double[]> points, List<StopDto> stops) {}

    /** All stops that share a name (one per direction or platform), grouped for search. */
    public record StopGroup(String name, List<String> ids, double lat, double lon) {}

    public record RideOption(String routeName, int directionId, String headsign, String shapeId,
                             String fromName, String toName, String fromStopId, String toStopId,
                             int stopsBetween, double rideSec) {}

    private final List<ShapeDto> shapes = new ArrayList<>();
    private final Map<String, String> headsigns = new HashMap<>();
    private final Map<String, StopGroup> groups = new LinkedHashMap<>();
    private final Map<String, Trip> repTrips = new LinkedHashMap<>();   // one representative trip per route direction

    public TransitIndex(GtfsData data) {
        Map<String, Map<String, double[]>> idsByName = new LinkedHashMap<>();
        Map<String, String> display = new HashMap<>();

        for (Trip trip : data.trips().values()) {
            if (!repTrips.containsKey(trip.shapeId())) {
                repTrips.put(trip.shapeId(), trip);
                Shape shape = data.shapes().get(trip.shapeId());
                List<double[]> pts = shape.points().stream()
                        .map(p -> new double[]{p.lat(), p.lon()}).toList();
                List<StopDto> stops = trip.stopTimes().stream()
                        .map(st -> new StopDto(st.stop().id(), st.stop().name(), st.stop().lat(), st.stop().lon()))
                        .toList();
                shapes.add(new ShapeDto(trip.shapeId(), trip.routeName(), trip.directionId(),
                        trip.headsign(), pts, stops));
                headsigns.put(trip.routeName() + "|" + trip.directionId(), trip.headsign());
            }
            for (StopTime st : trip.stopTimes()) {
                String key = st.stop().name().toLowerCase(Locale.ROOT);
                display.putIfAbsent(key, st.stop().name());
                idsByName.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .putIfAbsent(st.stop().id(), new double[]{st.stop().lat(), st.stop().lon()});
            }
        }
        idsByName.forEach((key, ids) -> {
            double lat = ids.values().stream().mapToDouble(c -> c[0]).average().orElse(0);
            double lon = ids.values().stream().mapToDouble(c -> c[1]).average().orElse(0);
            groups.put(key, new StopGroup(display.get(key), new ArrayList<>(ids.keySet()), lat, lon));
        });
    }

    public List<ShapeDto> shapes() { return shapes; }
    public int stopCount() { return groups.size(); }

    public int routeCount() {
        return (int) shapes.stream().map(s -> s.routeName().split(" ")[0]).distinct().count();
    }

    public String headsign(String routeName, int directionId) {
        return headsigns.getOrDefault(routeName + "|" + directionId, "");
    }

    public StopGroup group(String name) {
        return name == null ? null : groups.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public List<StopGroup> search(String q, int limit) {
        String s = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return List.of();
        List<StopGroup> starts = new ArrayList<>();
        List<StopGroup> contains = new ArrayList<>();
        for (Map.Entry<String, StopGroup> e : groups.entrySet()) {
            if (e.getKey().startsWith(s)) starts.add(e.getValue());
            else if (e.getKey().contains(s)) contains.add(e.getValue());
        }
        starts.addAll(contains);
        return starts.size() > limit ? new ArrayList<>(starts.subList(0, limit)) : starts;
    }

    /** Direct rides (no transfers): route directions that visit stop A and later stop B. */
    public List<RideOption> plan(String from, String to) {
        String f = from.trim().toLowerCase(Locale.ROOT);
        String t = to.trim().toLowerCase(Locale.ROOT);
        List<RideOption> out = new ArrayList<>();
        if (f.equals(t)) return out;
        for (Trip trip : repTrips.values()) {
            List<StopTime> sts = trip.stopTimes();
            int iFrom = -1;
            for (int i = 0; i < sts.size(); i++) {
                String nm = sts.get(i).stop().name().toLowerCase(Locale.ROOT);
                if (iFrom < 0 && nm.equals(f)) {
                    iFrom = i;
                } else if (iFrom >= 0 && nm.equals(t)) {
                    StopTime a = sts.get(iFrom);
                    StopTime b = sts.get(i);
                    out.add(new RideOption(trip.routeName(), trip.directionId(), trip.headsign(), trip.shapeId(),
                            a.stop().name(), b.stop().name(), a.stop().id(), b.stop().id(),
                            i - iFrom, b.scheduledSec() - a.scheduledSec()));
                    break;
                }
            }
        }
        return out;
    }
}