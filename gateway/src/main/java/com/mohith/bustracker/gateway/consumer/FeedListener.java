package com.mohith.bustracker.gateway.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mohith.bustracker.common.EtaUpdate;
import com.mohith.bustracker.common.Topics;
import com.mohith.bustracker.gateway.web.StreamHub;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FeedListener {
    private static final Logger log = LoggerFactory.getLogger(FeedListener.class);

    private final StreamHub hub;
    private final ObjectMapper mapper;

    public FeedListener(StreamHub hub, ObjectMapper mapper) {
        this.hub = hub;
        this.mapper = mapper;
    }

    // a unique group per gateway start, so every gateway instance receives every event
    @KafkaListener(topics = Topics.ETA,
            groupId = "#{'gateway-eta-' + T(java.util.UUID).randomUUID().toString()}")
    public void onEta(ConsumerRecord<String, String> rec) {
        try {
            hub.publishEta(mapper.readValue(rec.value(), EtaUpdate.class), rec.value());
        } catch (Exception e) {
            log.warn("Skipping unreadable ETA event: {}", e.toString());
        }
    }

    @KafkaListener(topics = Topics.ALERTS,
            groupId = "#{'gateway-alerts-' + T(java.util.UUID).randomUUID().toString()}")
    public void onAlert(ConsumerRecord<String, String> rec) {
        hub.publishAlert(rec.value());
    }
}