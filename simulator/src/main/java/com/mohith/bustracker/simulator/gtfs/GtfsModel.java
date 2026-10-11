package com.mohith.bustracker.simulator.gtfs;

import java.util.List;
import java.util.Map;

public final class GtfsModel {
    private GtfsModel() {}

    public record ShapePoint(double lat, double lon, double distM) {}

    public record Shape(String id, List<ShapePoint> points) {
        public double lengthM() { return points.get(points.size() - 1).distM(); }
    }

    public record Stop(String id, String name, double lat, double lon) {}

    /** A scheduled visit to a stop, plus how far along the shape that stop is. */
    public record StopTime(Stop stop, int sequence, int scheduledSec, double distAlongShapeM) {}

    public record Trip(String id, String routeId, String routeName, String shapeId,
                       String headsign, int directionId, List<StopTime> stopTimes) {
        public int startSec() { return stopTimes.get(0).scheduledSec(); }
        public int endSec() { return stopTimes.get(stopTimes.size() - 1).scheduledSec(); }
    }

    public record GtfsData(Map<String, Shape> shapes, Map<String, Trip> trips) {}
}