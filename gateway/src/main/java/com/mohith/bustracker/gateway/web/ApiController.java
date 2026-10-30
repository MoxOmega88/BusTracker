package com.mohith.bustracker.gateway.web;

import com.mohith.bustracker.common.gtfs.GtfsModel.GtfsData;
import com.mohith.bustracker.common.gtfs.GtfsModel.Shape;
import com.mohith.bustracker.common.gtfs.GtfsModel.Trip;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ApiController {
    public record StopDto(String id, String name, double lat, double lon) {}

    public record ShapeDto(String shapeId, String routeName, int directionId,
                           List<double[]> points, List<StopDto> stops) {}

    private final List<ShapeDto> shapes;
    private final StreamHub hub;

    public ApiController(GtfsData data, StreamHub hub) {
        this.hub = hub;
        Map<String, ShapeDto> byShape = new LinkedHashMap<>();
        for (Trip trip : data.trips().values()) {
            if (byShape.containsKey(trip.shapeId())) continue;
            Shape shape = data.shapes().get(trip.shapeId());
            List<double[]> points = shape.points().stream()
                    .map(p -> new double[]{p.lat(), p.lon()}).toList();
            List<StopDto> stops = trip.stopTimes().stream()
                    .map(st -> new StopDto(st.stop().id(), st.stop().name(),
                            st.stop().lat(), st.stop().lon())).toList();
            byShape.put(trip.shapeId(), new ShapeDto(trip.shapeId(), trip.routeName(),
                    trip.directionId(), points, stops));
        }
        this.shapes = new ArrayList<>(byShape.values());
    }

    @GetMapping("/api/routes")
    public List<ShapeDto> routes() {
        return shapes;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }
}