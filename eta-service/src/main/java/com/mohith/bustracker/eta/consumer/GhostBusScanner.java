package com.mohith.bustracker.eta.consumer;

import com.mohith.bustracker.common.BusAlert;
import com.mohith.bustracker.common.Topics;
import com.mohith.bustracker.eta.service.EtaProcessor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GhostBusScanner {
    private final EtaProcessor processor;
    private final KafkaTemplate<String, Object> kafka;

    public GhostBusScanner(EtaProcessor processor, KafkaTemplate<String, Object> kafka) {
        this.processor = processor;
        this.kafka = kafka;
    }

    @Scheduled(fixedRate = 2000)
    public void scan() {
        for (BusAlert a : processor.detectGhosts()) {
            kafka.send(Topics.ALERTS, a.vehicleId(), a);
        }
    }
}