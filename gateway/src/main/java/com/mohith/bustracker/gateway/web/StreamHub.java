package com.mohith.bustracker.gateway.web;

import com.mohith.bustracker.common.EtaUpdate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Holds the latest state and fans events out to every connected browser. */
@Component
public class StreamHub {
    private static final int MAX_ALERTS = 50;
    private static final long RESTART_GAP_MS = 600_000;
    private static final long STALE_MS = 120_000;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, String> latestJson = new ConcurrentHashMap<>();
    private final Map<String, EtaUpdate> latest = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> recentAlerts = new ConcurrentLinkedDeque<>();
    private final AtomicLong streamTs = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);   // never time out
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        latestJson.values().forEach(json -> send(emitter, "eta", json));   // snapshot for a page opened mid-run
        recentAlerts.forEach(json -> send(emitter, "alert", json));
        return emitter;
    }

    public void publishEta(EtaUpdate e, String json) {
        long cur = streamTs.get();
        if (cur > 0 && e.timestamp() < cur - RESTART_GAP_MS) {   // simulator restarted with an older clock
            latestJson.clear();
            latest.clear();
            recentAlerts.clear();
            alertCount.set(0);
            streamTs.set(e.timestamp());
            broadcast("reset", "{}");
        }
        streamTs.accumulateAndGet(e.timestamp(), Math::max);
        latest.put(e.vehicleId(), e);
        latestJson.put(e.vehicleId(), json);
        broadcast("eta", json);
    }

    public void publishAlert(String json) {
        alertCount.incrementAndGet();
        recentAlerts.addLast(json);
        while (recentAlerts.size() > MAX_ALERTS) recentAlerts.pollFirst();
        broadcast("alert", json);
    }

    /** Buses that reported recently (in stream time); silent ghost buses drop out. */
    public Collection<EtaUpdate> freshBuses() {
        long cutoff = streamTs.get() - STALE_MS;
        return latest.values().stream().filter(e -> e.timestamp() >= cutoff).toList();
    }

    public long simTime() { return streamTs.get(); }
    public long alertCount() { return alertCount.get(); }

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