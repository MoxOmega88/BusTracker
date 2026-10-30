package com.mohith.bustracker.eta.consumer;

import com.mohith.bustracker.common.EtaUpdate;
import com.mohith.bustracker.common.Topics;
import com.mohith.bustracker.common.VehiclePosition;
import com.mohith.bustracker.eta.service.EtaProcessor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class PositionListener {
    private final EtaProcessor processor;
    private final KafkaTemplate<String, EtaUpdate> kafka;

    public PositionListener(EtaProcessor processor, KafkaTemplate<String, EtaUpdate> kafka) {
        this.processor = processor;
        this.kafka = kafka;
    }

    @KafkaListener(topics = Topics.POSITIONS, concurrency = "3")
    public void onPosition(VehiclePosition p) {
        EtaUpdate update = processor.process(p);
        if (update != null) {
            kafka.send(Topics.ETA, update.vehicleId(), update);   // same key keeps per-bus order
        }
    }
}