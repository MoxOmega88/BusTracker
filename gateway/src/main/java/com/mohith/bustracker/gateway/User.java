package com.mohith.bustracker.gateway.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("users")
public record User(@Id String id, String username, String displayName, String passwordHash,
                   String googleSub, List<Place> places, Instant createdAt) {

    public record Place(String label, String stopName, double lat, double lon) {}

    public User withPlaces(List<Place> p) {
        return new User(id, username, displayName, passwordHash, googleSub, p, createdAt);
    }
}