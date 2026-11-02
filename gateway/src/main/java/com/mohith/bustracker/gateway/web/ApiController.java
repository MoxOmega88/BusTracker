package com.mohith.bustracker.gateway.web;

import com.mohith.bustracker.common.EtaUpdate;
import com.mohith.bustracker.common.StopEta;
import com.mohith.bustracker.gateway.web.TransitIndex.RideOption;
import com.mohith.bustracker.gateway.web.TransitIndex.ShapeDto;
import com.mohith.bustracker.gateway.web.TransitIndex.StopGroup;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
public class ApiController {
    public record Arrival(String vehicleId, String routeName, int directionId, String headsign,
                          double etaSec, double timetableSec, double distM,
                          boolean delayed, String status, int stopsAway) {}

    public record LiveBus(String vehicleId, double boardSec, double destSec, boolean delayed) {}

    public record PlanOption(String routeName, int directionId, String headsign, String shapeId,
                             String fromName, String toName, int stopsBetween, double rideSec,
                             List<LiveBus> buses) {}

    public record PlanResponse(StopGroup from, StopGroup to, List<PlanOption> options) {}

    public record BoardRow(String route, String headsign, String nextStop, double etaSec) {}

    public record Stats(int buses, int routes, int stops, long alerts, long simTime, List<BoardRow> board) {}

    private final TransitIndex index;
    private final StreamHub hub;

    public ApiController(TransitIndex index, StreamHub hub) {
        this.index = index;
        this.hub = hub;
    }

    @GetMapping("/api/routes")
    public List<ShapeDto> routes() {
        return index.shapes();
    }

    @GetMapping("/api/stops")
    public List<StopGroup> stops(@RequestParam(defaultValue = "") String q) {
        return index.search(q, 8);
    }

    /** Next buses to reach any of the given stop ids, soonest first. */
    @GetMapping("/api/arrivals")
    public List<Arrival> arrivals(@RequestParam String ids) {
        Set<String> want = new HashSet<>(Arrays.asList(ids.split(",")));
        List<Arrival> out = new ArrayList<>();
        for (EtaUpdate e : hub.freshBuses()) {
            List<StopEta> up = e.upcoming();
            for (int i = 0; i < up.size(); i++) {
                StopEta s = up.get(i);
                if (want.contains(s.stopId())) {
                    out.add(new Arrival(e.vehicleId(), e.routeName(), e.directionId(),
                            index.headsign(e.routeName(), e.directionId()),
                            s.correctedEtaSec(), s.scheduledEtaSec(), s.distToStopM(),
                            e.delayed(), e.status(), i));
                    break;
                }
            }
        }
        out.sort(Comparator.comparingDouble(Arrival::etaSec));
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    /** Direct rides from one stop to another, with the live buses that will make the trip. */
    @GetMapping("/api/plan")
    public PlanResponse plan(@RequestParam String from, @RequestParam String to) {
        StopGroup f = index.group(from);
        StopGroup t = index.group(to);
        List<PlanOption> options = new ArrayList<>();
        if (f != null && t != null) {
            for (RideOption r : index.plan(from, to)) {
                List<LiveBus> live = new ArrayList<>();
                for (EtaUpdate e : hub.freshBuses()) {
                    if (!e.routeName().equals(r.routeName()) || e.directionId() != r.directionId()) continue;
                    StopEta a = find(e.upcoming(), r.fromStopId());
                    StopEta b = find(e.upcoming(), r.toStopId());
                    if (a != null && b != null) {
                        live.add(new LiveBus(e.vehicleId(), a.correctedEtaSec(), b.correctedEtaSec(), e.delayed()));
                    }
                }
                live.sort(Comparator.comparingDouble(LiveBus::boardSec));
                options.add(new PlanOption(r.routeName(), r.directionId(), r.headsign(), r.shapeId(),
                        r.fromName(), r.toName(), r.stopsBetween(), r.rideSec(),
                        live.size() > 3 ? new ArrayList<>(live.subList(0, 3)) : live));
            }
        }
        return new PlanResponse(f, t, options);
    }

    @GetMapping("/api/stats")
    public Stats stats() {
        Collection<EtaUpdate> fresh = hub.freshBuses();
        List<BoardRow> board = new ArrayList<>();
        for (EtaUpdate e : fresh) {
            if (e.upcoming().isEmpty()) continue;
            StopEta next = e.upcoming().get(0);
            board.add(new BoardRow(e.routeName().split(" ")[0], index.headsign(e.routeName(), e.directionId()),
                    next.stopName(), next.correctedEtaSec()));
        }
        board.sort(Comparator.comparingDouble(BoardRow::etaSec));
        return new Stats(fresh.size(), index.routeCount(), index.stopCount(), hub.alertCount(), hub.simTime(),
                board.size() > 5 ? new ArrayList<>(board.subList(0, 5)) : board);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return hub.subscribe();
    }

    private static StopEta find(List<StopEta> list, String stopId) {
        for (StopEta s : list) if (s.stopId().equals(stopId)) return s;
        return null;
    }
}