package com.example.cookiedemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * ==========================================================================
 *  EVERYTHING ABOUT SESSIONS, IN ONE PLACE
 * ==========================================================================
 *  Four jobs:
 *
 *    mint    -> make a brand-new random token and save its hash as a row
 *    lookup  -> take a token from a cookie and find the row it points at
 *    revoke  -> delete the row, so the token instantly stops working
 *    purge   -> sweep away rows whose deadline has passed
 *
 *  The controller never sees a raw database row without going through here,
 *  which means the expiry check can never be forgotten.
 * ==========================================================================
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    /**
     * 32 bytes = 256 bits of randomness.
     *
     * This is the number that has to be unguessable, so it is worth being
     * precise about why. A short id (say 6 digits) could be brute-forced by a
     * script trying a million cookies. At 256 bits there are more possible
     * tokens than atoms in the observable universe, so guessing one is not a
     * thing that happens — the attack has to shift to stealing the cookie
     * instead, which is exactly what HttpOnly / Secure / SameSite are for.
     */
    private static final int TOKEN_BYTES = 32;

    /**
     * SecureRandom, NOT java.util.Random.
     *
     * Random is a predictable formula seeded from the clock: see a few outputs
     * and you can compute every future one. SecureRandom pulls from the
     * operating system's entropy pool. For anything that acts as a password,
     * this distinction is the whole ballgame.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** URL-safe Base64 with no "=" padding, so the token is legal in a cookie without escaping. */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SessionRepository sessions;

    /** How long a session may live on the server, regardless of what the browser does. */
    private final Duration ttl;

    public SessionService(SessionRepository sessions,
                          @Value("${app.session.ttl-minutes:43200}") long ttlMinutes) {
        this.sessions = sessions;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    /** What {@link #mint} hands back: the secret for the browser, and the row we stored. */
    public record IssuedSession(String token, UserSession session) {
    }

    // ======================================================================
    //  MINT — create a new session
    // ======================================================================
    @Transactional
    public IssuedSession mint(AppUser user, int visits) {

        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = ENCODER.encodeToString(bytes);   // this goes to the browser

        Instant now = Instant.now();
        UserSession row = new UserSession(hash(token), user, visits, now, now.plus(ttl));
        sessions.save(row);                             // only the HASH goes to the database

        log.debug("Session started for {} (expires {})", user.getUsername(), row.getExpiresAt());
        return new IssuedSession(token, row);
    }

    // ======================================================================
    //  LOOKUP — turn a cookie value back into a user
    // ======================================================================

    /**
     * The heart of the whole thing: hash the token from the cookie, look the
     * hash up, and hand back the row — but only if it has not expired.
     *
     * An empty result means exactly one thing to the caller: "this browser is
     * not signed in." It does not matter whether the cookie was missing,
     * garbage, invented, or simply too old; all four are the same answer, and
     * deliberately so — telling an attacker which of those went wrong is free
     * information they should not get.
     */
    @Transactional
    public Optional<UserSession> lookup(String token) {

        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        Optional<UserSession> found = sessions.findById(hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        UserSession session = found.get();
        Instant now = Instant.now();

        // Expiry is enforced HERE, on the server. Never trust the browser to
        // have honoured the cookie's Max-Age — a saved cookie can be replayed
        // long after it was supposed to have been thrown away.
        if (session.isExpired(now)) {
            sessions.delete(session);
            log.debug("Rejected an expired session for {}", session.getUsername());
            return Optional.empty();
        }

        session.setLastSeenAt(now);   // inside @Transactional, JPA writes this back for us
        return Optional.of(session);
    }

    // ======================================================================
    //  REVOKE — make a token stop working
    // ======================================================================

    /**
     * This is the capability the old design simply did not have.
     *
     * When the data lived in the cookie, "logging out" meant asking the browser
     * nicely to delete it — and if a copy had already been taken, that copy
     * kept working forever. Now the truth is a row on our side: delete the row
     * and every copy of the cookie is dead the moment the next request lands.
     */
    @Transactional
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteById(hash(token));
        }
    }

    /** Kills every session for an account, on every device. */
    @Transactional
    public long revokeAllFor(String username) {
        return sessions.deleteByUsername(username);
    }

    // ======================================================================
    //  PURGE — housekeeping
    // ======================================================================

    /**
     * Expired rows are already rejected on read, so this is not a security
     * control — it just stops the table growing forever with dead sessions.
     * Runs an hour after startup and hourly after that.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    @Transactional
    public void purgeExpired() {
        long removed = sessions.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired session(s)", removed);
        }
    }

    // ======================================================================
    //  THE HASH
    // ======================================================================

    /**
     * SHA-256, hex-encoded. Public because the tests assert on it: they check
     * that the raw cookie value is nowhere in the database and that the hash
     * of it is.
     */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required to exist on every JVM, so this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
