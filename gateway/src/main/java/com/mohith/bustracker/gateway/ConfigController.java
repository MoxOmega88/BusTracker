package com.mohith.bustracker.gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {
    record Cfg(boolean authEnabled, String googleClientId) {}

    private final Cfg cfg;

    public ConfigController(@Value("${bustracker.auth.enabled:false}") boolean enabled,
                            @Value("${bustracker.auth.google-client-id:}") String googleClientId) {
        this.cfg = new Cfg(enabled, googleClientId);
    }

    @GetMapping("/api/config")
    public Cfg config() { return cfg; }
}