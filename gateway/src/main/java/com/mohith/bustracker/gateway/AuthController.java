package com.mohith.bustracker.gateway.auth;

import com.mohith.bustracker.gateway.web.TransitIndex;
import com.mohith.bustracker.gateway.web.TransitIndex.Nearby;
import com.mohith.bustracker.gateway.web.TransitIndex.StopGroup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "bustracker.auth.enabled", havingValue = "true")
public class AuthController {
    record Creds(String username, String password, String displayName) {}
    record GoogleReq(String credential) {}
    record PlaceReq(String label, String stopName) {}
    record Profile(String username, String displayName, boolean google, List<User.Place> places) {}
    record AuthResp(String token, Profile user) {}

    private final AuthService auth;
    private final TransitIndex index;

    public AuthController(AuthService auth, TransitIndex index) {
        this.auth = auth;
        this.index = index;
    }

    @PostMapping("/auth/signup")
    AuthResp signup(@RequestBody Creds c) {
        return resp(auth.signup(c.username(), c.password(), c.displayName()));
    }

    @PostMapping("/auth/login")
    AuthResp login(@RequestBody Creds c) {
        return resp(auth.login(c.username(), c.password()));
    }

    @PostMapping("/auth/google")
    AuthResp google(@RequestBody GoogleReq r) {
        return resp(auth.googleLogin(r.credential()));
    }

    @GetMapping("/me")
    Profile me(@RequestHeader(value = "Authorization", required = false) String h) {
        return profile(auth.userFrom(h));
    }

    @PutMapping("/me/places")
    Profile places(@RequestHeader(value = "Authorization", required = false) String h, @RequestBody List<PlaceReq> req) {
        User u = auth.userFrom(h);
        if (req.size() > 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 3 places");
        List<User.Place> out = new ArrayList<>();
        for (PlaceReq p : req) {
            StopGroup g = index.group(p.stopName());
            if (g == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown stop: " + p.stopName());
            String label = p.label() == null ? "Place" : p.label().trim();
            out.add(new User.Place(label.length() > 20 ? label.substring(0, 20) : label, g.name(), g.lat(), g.lon()));
        }
        return profile(auth.savePlaces(u, out));
    }

    @GetMapping("/me/nearby")
    List<Nearby> nearby(@RequestHeader(value = "Authorization", required = false) String h,
                        @RequestParam(defaultValue = "800") double radius) {
        User u = auth.userFrom(h);
        Map<String, Nearby> best = new HashMap<>();
        for (User.Place p : u.places() == null ? List.<User.Place>of() : u.places()) {
            for (Nearby n : index.nearby(p.lat(), p.lon(), p.label(), radius)) {
                best.merge(n.routeLabel(), n, (a, b) -> a.distM() <= b.distM() ? a : b);
            }
        }
        return best.values().stream().sorted(Comparator.comparingInt(Nearby::distM)).toList();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> onError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("message", e.getReason() == null ? "Error" : e.getReason()));
    }

    private AuthResp resp(User u) {
        return new AuthResp(auth.token(u), profile(u));
    }

    private static Profile profile(User u) {
        return new Profile(u.username(), u.displayName(), u.googleSub() != null,
                u.places() == null ? List.of() : u.places());
    }
}