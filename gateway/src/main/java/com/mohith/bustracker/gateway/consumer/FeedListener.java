package com.mohith.bustracker.gateway.consumer;

import com.mohith.bustracker.common.Topics;
import com.mohith.bustracker.gateway.web.StreamHub;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FeedListener {
    private final StreamHub hub;

    public FeedListener(StreamHub hub) {
        this.hub = hub;
    }

    // a unique group per gateway start, so every gateway instance receives every event
    @KafkaListener(topics = Topics.ETA,
            groupId = "#{'gateway-eta-' + T(java.util.UUID).randomUUID().toString()}")
    public void onEta(ConsumerRecord<String, String> rec) {
        hub.publishEta(rec.key(), rec.value());
    }

    @KafkaListener(topics = Topics.ALERTS,
            groupId = "#{'gateway-alerts-' + T(java.util.UUID).randomUUID().toString()}")
    public void onAlert(ConsumerRecord<String, String> rec) {
        hub.publishAlert(rec.value());
    }
}