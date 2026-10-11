package com.mohith.bustracker.simulator.engine;

import com.mohith.bustracker.simulator.gtfs.GtfsModel.Shape;
import com.mohith.bustracker.simulator.gtfs.GtfsModel.ShapePoint;
import java.util.List;

public final class RouteGeometry {
    private RouteGeometry() {}

    /** Returns {lat, lon} at the given distance (metres) along the shape. */
    public static double[] pointAt(Shape shape, double distM) {
        List<ShapePoint> p = shape.points();
        if (distM <= 0) return new double[]{p.get(0).lat(), p.get(0).lon()};
        if (distM >= shape.lengthM()) {
            ShapePoint last = p.get(p.size() - 1);
            return new double[]{last.lat(), last.lon()};
        }
        int lo = 0, hi = p.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (p.get(mid).distM() <= distM) lo = mid; else hi = mid;
        }
        ShapePoint a = p.get(lo), b = p.get(hi);
        double seg = b.distM() - a.distM();
        double f = seg <= 0 ? 0 : (distM - a.distM()) / seg;
        return new double[]{a.lat() + (b.lat() - a.lat()) * f, a.lon() + (b.lon() - a.lon()) * f};
    }
}