package com.mohith.bustracker.simulator.gtfs;

import com.mohith.bustracker.simulator.gtfs.GtfsModel.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
public class GtfsLoader {
    private static final Logger log = LoggerFactory.getLogger(GtfsLoader.class);
    private static final double SNAP_WARN_M = 150;

    private record RawStopTime(String stopId, int seq, int sec) {}

    private final Path dir;

    public GtfsLoader(@Value("${bustracker.data-dir}") String dir) {
        this.dir = Path.of(dir);
    }

    public GtfsData load() throws IOException {
        Map<String, String> routeNames = loadRouteNames();
        Map<String, Stop> stops = loadStops();
        Map<String, Shape> shapes = loadShapes();
        Map<String, List<RawStopTime>> stopTimes = loadStopTimes();

        Map<String, Trip> trips = new LinkedHashMap<>();
        int farSnaps = 0;
        for (CSVRecord r : read("trips.txt")) {
            String tripId = r.get("trip_id");
            Shape shape = shapes.get(r.get("shape_id"));
            List<RawStopTime> raw = stopTimes.get(tripId);
            if (shape == null || raw == null || raw.isEmpty()) {
                log.warn("Skipping trip {}: missing shape or stop times", tripId);
                continue;
            }
            List<StopTime> snapped = snap(raw, stops, shape);
            for (StopTime st : snapped) {
                // distance from stop to its matched shape point is checked inside snap(); count via log
            }
            if (snapped.isEmpty()) continue;
            trips.put(tripId, new Trip(
                    tripId, r.get("route_id"),
                    routeNames.getOrDefault(r.get("route_id"), r.get("route_id")),
                    shape.id(), r.get("trip_headsign"),
                    Integer.parseInt(r.get("direction_id")), snapped));
        }
        return new GtfsData(shapes, trips);
    }

    /** Match each stop to the nearest shape point, never moving backwards along the route. */
    private List<StopTime> snap(List<RawStopTime> raw, Map<String, Stop> stops, Shape shape) {
        List<ShapePoint> pts = shape.points();
        List<StopTime> out = new ArrayList<>();
        int from = 0;
        for (RawStopTime r : raw) {
            Stop s = stops.get(r.stopId());
            if (s == null) continue;
            int best = from;
            double bestD = Double.MAX_VALUE;
            for (int i = from; i < pts.size(); i++) {
                double d = Geo.meters(s.lat(), s.lon(), pts.get(i).lat(), pts.get(i).lon());
                if (d < bestD) { bestD = d; best = i; }
            }
            if (bestD > SNAP_WARN_M) {
                log.warn("Stop {} is {} m from shape {}", s.id(), Math.round(bestD), shape.id());
            }
            from = best;
            out.add(new StopTime(s, r.seq(), r.sec(), pts.get(best).distM()));
        }
        return out;
    }

    private Map<String, String> loadRouteNames() throws IOException {
        Map<String, String> m = new HashMap<>();
        for (CSVRecord r : read("routes.txt")) {
            m.put(r.get("route_id"), opt(r, "route_short_name", r.get("route_id")));
        }
        return m;
    }

    private Map<String, Stop> loadStops() throws IOException {
        Map<String, Stop> m = new HashMap<>();
        for (CSVRecord r : read("stops.txt")) {
            m.put(r.get("stop_id"), new Stop(r.get("stop_id"), opt(r, "stop_name", ""),
                    Double.parseDouble(r.get("stop_lat")), Double.parseDouble(r.get("stop_lon"))));
        }
        return m;
    }

    private Map<String, Shape> loadShapes() throws IOException {
        record RawPt(int seq, double lat, double lon) {}
        Map<String, List<RawPt>> grouped = new HashMap<>();
        for (CSVRecord r : read("shapes.txt")) {
            grouped.computeIfAbsent(r.get("shape_id"), k -> new ArrayList<>())
                   .add(new RawPt(Integer.parseInt(r.get("shape_pt_sequence")),
                           Double.parseDouble(r.get("shape_pt_lat")),
                           Double.parseDouble(r.get("shape_pt_lon"))));
        }
        Map<String, Shape> shapes = new HashMap<>();
        grouped.forEach((id, list) -> {
            list.sort(Comparator.comparingInt(RawPt::seq));
            List<ShapePoint> pts = new ArrayList<>();
            double dist = 0;
            RawPt prev = null;
            for (RawPt p : list) {
                if (prev != null) dist += Geo.meters(prev.lat(), prev.lon(), p.lat(), p.lon());
                pts.add(new ShapePoint(p.lat(), p.lon(), dist));
                prev = p;
            }
            shapes.put(id, new Shape(id, pts));
        });
        return shapes;
    }

    private Map<String, List<RawStopTime>> loadStopTimes() throws IOException {
        Map<String, List<RawStopTime>> m = new HashMap<>();
        for (CSVRecord r : read("stop_times.txt")) {
            m.computeIfAbsent(r.get("trip_id"), k -> new ArrayList<>())
             .add(new RawStopTime(r.get("stop_id"), Integer.parseInt(r.get("stop_sequence")),
                     parseSec(r.get("arrival_time"))));
        }
        m.values().forEach(l -> l.sort(Comparator.comparingInt(RawStopTime::seq)));
        return m;
    }

    /** "09:10:00" -> seconds since midnight. Hours may exceed 24 in GTFS. */
    static int parseSec(String t) {
        String[] p = t.trim().split(":");
        return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
    }

    private static String opt(CSVRecord r, String col, String def) {
        return r.isMapped(col) && !r.get(col).isBlank() ? r.get(col) : def;
    }

    private List<CSVRecord> read(String file) throws IOException {
        try (Reader reader = Files.newBufferedReader(dir.resolve(file), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).setTrim(true).build().parse(reader)) {
            return parser.getRecords();
        }
    }
}