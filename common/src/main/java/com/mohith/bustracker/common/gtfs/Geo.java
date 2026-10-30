package com.mohith.bustracker.simulator.gtfs;

public final class Geo {
    private Geo() {}

    /** Fast distance approximation, accurate enough at city scale. */
    public static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return 6_371_000 * Math.sqrt(dLat * dLat + dLon * dLon);
    }
}