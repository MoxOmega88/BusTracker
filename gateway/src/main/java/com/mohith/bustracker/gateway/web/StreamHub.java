package com.mohith.bustracker.gateway.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/** Holds the latest state and fans events out to every connected browser. */
@Component
public class StreamHub {
    private static final int MAX_ALERTS = 50;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, String> latestEta = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recentAlerts = new ConcurrentLinkedDeque<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);   // 0 = never time out
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        // snapshot so a new page is populated immediately
        latestEta.values().forEach(json -> send(emitter, "eta", json));
        recentAlerts.forEach(json -> send(emitter, "alert", json));
        return emitter;
    }

    public void publishEta(String vehicleId, String json) {
        latestEta.put(vehicleId, json);
        broadcast("eta", json);
    }

    public void publishAlert(String json) {
        recentAlerts.addLast(json);
        while (recentAlerts.size() > MAX_ALERTS) recentAlerts.pollFirst();
        broadcast("alert", json);
    }

    private void broadcast(String name, String json) {
        for (SseEmitter emitter : emitters) send(emitter, name, json);
    }

    private void send(SseEmitter emitter, String name, String json) {
        try {
            synchronized (emitter) {   // two listener threads may write to the same client
                emitter.send(SseEmitter.event().name(name).data(json));
            }
        } catch (Exception e) {
            emitters.remove(emitter);
        }
    }
}