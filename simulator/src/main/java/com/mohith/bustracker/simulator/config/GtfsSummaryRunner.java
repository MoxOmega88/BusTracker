package com.mohith.bustracker.simulator.config;

import com.mohith.bustracker.simulator.gtfs.GtfsLoader;
import com.mohith.bustracker.simulator.gtfs.GtfsModel.GtfsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class GtfsSummaryRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(GtfsSummaryRunner.class);
    private final GtfsLoader loader;

    public GtfsSummaryRunner(GtfsLoader loader) { this.loader = loader; }

    @Override
    public void run(String... args) throws Exception {
        GtfsData d = loader.load();
        log.info("Loaded {} shapes and {} trips", d.shapes().size(), d.trips().size());
        d.trips().values().stream().limit(6).forEach(t -> {
            var last = t.stopTimes().get(t.stopTimes().size() - 1);
            log.info("trip {} route {} shape '{}' stops={} start={}s lastStopAt={}m shapeLen={}m",
                    t.id(), t.routeName(), t.shapeId(), t.stopTimes().size(),
                    t.startSec(), Math.round(last.distAlongShapeM()),
                    Math.round(d.shapes().get(t.shapeId()).lengthM()));
        });
    }
}