package com.mohith.bustracker.gateway.auth;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "bustracker.auth.enabled", havingValue = "true")
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_.@-]{3,60}$");

    private final MongoTemplate mongo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecretKey key;
    private final GoogleIdTokenVerifier google;   // null when Google sign-in isn't configured

    public AuthService(MongoTemplate mongo,
                       @Value("${bustracker.auth.jwt-secret}") String secret,
                       @Value("${bustracker.auth.google-client-id:}") String googleClientId) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 characters long");
        }
        this.mongo = mongo;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.google = googleClientId.isBlank() ? null
                : new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(List.of(googleClientId)).build();
    }

    @PostConstruct
    void indexes() {
        try {
            mongo.indexOps(User.class).ensureIndex(new Index().on("username", Sort.Direction.ASC).unique());
            mongo.indexOps(User.class).ensureIndex(new Index().on("googleSub", Sort.Direction.ASC).unique().sparse());
        } catch (Exception e) {
            log.warn("Could not create MongoDB indexes: {}", e.toString());
        }
    }

    public User signup(String username, String password, String displayName) {
        String u = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) throw bad("Username: 3-60 characters, letters, digits and . _ - @");
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw bad("Password must be 8 to 72 characters");
        }
        String name = (displayName == null || displayName.isBlank()) ? u : displayName.trim();
        if (name.length() > 40) name = name.substring(0, 40);
        try {
            return mongo.insert(new User(null, u, name, encoder.encode(password), null, List.of(), Instant.now()));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is taken");
        }
    }

    public User login(String username, String password) {
        User u = username == null ? null : mongo.findOne(
                Query.query(Criteria.where("username").is(username.trim().toLowerCase(Locale.ROOT))), User.class);
        if (u == null || u.passwordHash() == null || password == null || !encoder.matches(password, u.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        return u;
    }

    public User googleLogin(String credential) {
        if (google == null) throw bad("Google sign-in is not configured");
        GoogleIdToken.Payload p;
        try {
            GoogleIdToken t = google.verify(credential);
            if (t == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token");
            p = t.getPayload();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Could not verify the Google token");
        }
        if (!Boolean.TRUE.equals(p.getEmailVerified())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google email is not verified");
        }
        String sub = p.getSubject();
        User existing = mongo.findOne(Query.query(Criteria.where("googleSub").is(sub)), User.class);
        if (existing != null) return existing;
        Object n = p.get("name");
        String name = n instanceof String s && !s.isBlank() ? s : "Google user";
        // the username can't clash with a password account: ':' is not allowed in usernames, so no account linking by email
        return mongo.insert(new User(null, "google:" + sub, name, null, sub, List.of(), Instant.now()));
    }

    public String token(User u) {
        Instant now = Instant.now();
        return Jwts.builder().subject(u.id()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(7, ChronoUnit.DAYS))).signWith(key).compact();
    }

    public User userFrom(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required");
        }
        try {
            String id = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authHeader.substring(7)).getPayload().getSubject();
            User u = mongo.findById(id, User.class);
            if (u != null) return u;
        } catch (JwtException | IllegalArgumentException e) {
            // fall through
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired, please sign in again");
    }

    public User savePlaces(User u, List<User.Place> places) {
        return mongo.save(u.withPlaces(places));
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}