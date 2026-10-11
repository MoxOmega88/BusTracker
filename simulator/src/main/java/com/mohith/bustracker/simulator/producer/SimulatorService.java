package com.mohith.bustracker.simulator.producer;

import com.mohith.bustracker.common.Topics;
import com.mohith.bustracker.common.VehiclePosition;
import com.mohith.bustracker.simulator.engine.SimBus;
import com.mohith.bustracker.simulator.gtfs.GtfsLoader;
import com.mohith.bustracker.simulator.gtfs.GtfsModel.GtfsData;
import com.mohith.bustracker.simulator.gtfs.GtfsModel.Trip;
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
    private final double speedup;
    private final double staggerSec;
    private final List<SimBus> buses = new ArrayList<>();
    private long simNowMs;
    private final AtomicLong sent = new AtomicLong();
    private long ticks = 0;

    public SimulatorService(KafkaTemplate<String, VehiclePosition> kafka, GtfsLoader loader,
                            @Value("${bustracker.sim.speedup:10}") double speedup,
                            @Value("${bustracker.sim.stagger-sec:15}") double staggerSec) {
        this.kafka = kafka;
        this.loader = loader;
        this.speedup = speedup;
        this.staggerSec = staggerSec;
    }

    @PostConstruct
    void init() throws IOException {
        GtfsData data = loader.load();
        Random rnd = new Random(42);
        Map<String, Integer> perShape = new HashMap<>();
        for (Trip trip : data.trips().values()) {
            int idx = perShape.merge(trip.shapeId(), 1, Integer::sum) - 1;
            buses.add(new SimBus(trip, data.shapes().get(trip.shapeId()), idx * staggerSec, rnd));
        }
        simNowMs = System.currentTimeMillis();
        log.info("Simulating {} buses at {}x speed", buses.size(), speedup);
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        double wallDt = 1.0;
        double simDt = wallDt * speedup;
        simNowMs += (long) (simDt * 1000);
        for (SimBus bus : buses) {
            VehiclePosition p = bus.tick(wallDt, simDt, simNowMs);
            if (p != null) {
                kafka.send(Topics.POSITIONS, p.vehicleId(), p).whenComplete((res, ex) -> {
                    if (ex != null) log.error("Send failed for {}: {}", p.vehicleId(), ex.toString());
                    else sent.incrementAndGet();
                });
            }
        }
        if (++ticks % 5 == 0) log.info("tick {} - confirmed sent so far: {}", ticks, sent.get());
    }
}