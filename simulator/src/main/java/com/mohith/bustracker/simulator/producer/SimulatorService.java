package com.mohith.bustracker.simulator.producer;

import com.mohith.bustracker.common.Topics;
import com.mohith.bustracker.common.VehiclePosition;
import com.mohith.bustracker.common.gtfs.GtfsLoader;
import com.mohith.bustracker.common.gtfs.GtfsModel.GtfsData;
import com.mohith.bustracker.common.gtfs.GtfsModel.Trip;
import com.mohith.bustracker.simulator.engine.IncidentPlan;
import com.mohith.bustracker.simulator.engine.SimBus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SimulatorService {
    private static final Logger log = LoggerFactory.getLogger(SimulatorService.class);

    private final KafkaTemplate<String, VehiclePosition> kafka;
    private final GtfsLoader loader;
    private final IncidentPlan plan;
    private final double speedup;
    private final double staggerSec;
    private final SimBus.Params params;
    private final List<SimBus> buses = new ArrayList<>();
    private final AtomicLong sent = new AtomicLong();
    private Set<String> lastActive = new HashSet<>();
    private long simStartMs;
    private long simNowMs;
    private long ticks = 0;

    public SimulatorService(KafkaTemplate<String, VehiclePosition> kafka, GtfsLoader loader, IncidentPlan plan,
                            @Value("${bustracker.sim.speedup:10}") double speedup,
                            @Value("${bustracker.sim.stagger-sec:15}") double staggerSec,
                            @Value("${bustracker.sim.cruise-mps:5.4}") double cruiseMps,
                            @Value("${bustracker.sim.dwell-min-sec:20}") double dwellMin,
                            @Value("${bustracker.sim.dwell-max-sec:45}") double dwellMax,
                            @Value("${bustracker.sim.red-prob:0.45}") double redProb) {
        this.kafka = kafka;
        this.loader = loader;
        this.plan = plan;
        this.speedup = speedup;
        this.staggerSec = staggerSec;
        this.params = new SimBus.Params(cruiseMps, 0.7, dwellMin, dwellMax, redProb, 15, 40);
    }

    @PostConstruct
    void init() throws IOException {
        GtfsData data = loader.load();
        Random rnd = new Random(42);
        Map<String, Integer> perShape = new HashMap<>();
        for (Trip trip : data.trips().values()) {
            int idx = perShape.merge(trip.shapeId(), 1, Integer::sum) - 1;
            buses.add(new SimBus(trip, data.shapes().get(trip.shapeId()), idx * staggerSec, rnd, plan, params));
        }
        simStartMs = System.currentTimeMillis();
        simNowMs = simStartMs;
        log.info("Simulating {} buses at {}x speed", buses.size(), speedup);
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        double wallDt = 1.0;
        double simDt = wallDt * speedup;
        simNowMs += (long) (simDt * 1000);
        double elapsed = (simNowMs - simStartMs) / 1000.0;
        logIncidentChanges(elapsed);

        for (SimBus bus : buses) {
            VehiclePosition p = bus.tick(wallDt, simDt, simNowMs, elapsed);
            if (p != null) {
                kafka.send(Topics.POSITIONS, p.vehicleId(), p).whenComplete((res, ex) -> {
                    if (ex != null) log.error("Send failed for {}: {}", p.vehicleId(), ex.toString());
                    else sent.incrementAndGet();
                });
            }
        }
        if (++ticks % 10 == 0) log.info("tick {} - confirmed sent so far: {}", ticks, sent.get());
    }

    private void logIncidentChanges(double elapsed) {
        Set<String> now = plan.activeIds(elapsed);
        for (String id : now) {
            if (!lastActive.contains(id)) log.warn("INCIDENT STARTED: {} (sim t={}s)", id, Math.round(elapsed));
        }
        for (String id : lastActive) {
            if (!now.contains(id)) log.warn("INCIDENT ENDED: {} (sim t={}s)", id, Math.round(elapsed));
        }
        lastActive = now;
    }
}