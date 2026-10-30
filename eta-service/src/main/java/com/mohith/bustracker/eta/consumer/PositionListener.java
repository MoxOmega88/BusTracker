package com.mohith.bustracker.eta.consumer;

import com.mohith.bustracker.common.BusAlert;
import com.mohith.bustracker.common.Topics;
import com.mohith.bustracker.common.VehiclePosition;
import com.mohith.bustracker.eta.service.EtaProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PositionListener {
    private final EtaProcessor processor;
    private final KafkaTemplate<String, Object> kafka;

    public PositionListener(EtaProcessor processor, KafkaTemplate<String, Object> kafka) {
        this.processor = processor;
        this.kafka = kafka;
    }

    @KafkaListener(topics = Topics.POSITIONS, concurrency = "3")
    public void onPosition(VehiclePosition p) {
        EtaProcessor.Result r = processor.process(p);
        if (r == null) return;
        kafka.send(Topics.ETA, r.update().vehicleId(), r.update());   // same key keeps per-bus order
        for (BusAlert a : r.alerts()) {
            kafka.send(Topics.ALERTS, a.vehicleId(), a);
        }
    }
}