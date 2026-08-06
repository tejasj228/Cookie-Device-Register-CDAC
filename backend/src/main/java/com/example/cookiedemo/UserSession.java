package com.example.cookiedemo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row in the SESSION table = one signed-in browser.
 *
 * ==========================================================================
 *  THIS CLASS IS THE CHANGE
 * ==========================================================================
 *  Before, the cookie itself carried the payload:
 *
 *      Cookie: userInfo=tejas%7CTejas+Jaiswal%7Ctejas%40mail.com%7C3
 *                       ^^^^^^^^^^^^ the user's data, sitting on their disk,
 *                                    readable and editable by anyone
 *
 *  Now the cookie carries nothing but a random number, and THIS ROW holds the
 *  data on the server:
 *
 *      Cookie: sid=Zk3n1Qw...            (137 bits of randomness, means nothing)
 *      DB row: id=<sha256 of that>, username=tejas, fullName=..., visits=3
 *
 *  The browser can no longer read its own profile, forge a different name, or
 *  bump its own visit counter — none of that data is in the browser any more.
 *
 * ==========================================================================
 *  WHY THE ID COLUMN IS A HASH AND NOT THE RAW TOKEN
 * ==========================================================================
 *  The raw token is a live credential: whoever holds it IS the session. If we
 *  stored it as-is, anyone who ever saw the table — a leaked backup, a log
 *  line, a stray screenshot of the H2 console — could paste it into their own
 *  browser and be signed in as that user.
 *
 *  So we store SHA-256(token) instead. On every request we hash the incoming
 *  cookie and look up the hash. A hash lookup still matches exactly, but the
 *  table now contains only a fingerprint that cannot be turned back into a
 *  working cookie. Same idea as hashing passwords, applied to session tokens.
 *
 *  (Plain SHA-256 is right here, and BCrypt would be wrong: the token is
 *  already 256 bits of true randomness, so there is nothing to brute-force
 *  and no reason to pay for a slow hash on every single request.)
 * ==========================================================================
 */
@Entity
@Table(name = "user_session", indexes = @Index(name = "idx_session_username", columnList = "username"))
public class UserSession {

    /** SHA-256 of the token in the cookie, hex-encoded. 64 characters. */
    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String id;

    /** Which account this session belongs to. */
    @Column(nullable = false)
    private String username;

    /** Copied from the user row when the session starts — what the cookie used to hold. */
    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    /**
     * The account's visit count at the moment this session started — a
     * snapshot, not a live number.
     *
     * The counter itself lives on {@link AppUser} and keeps climbing after this
     * row is written, so do not read it from here expecting it to be current.
     * It is kept because it makes the session table readable: you can see at a
     * glance how far along an account was when each of its sessions began.
     */
    @Column(nullable = false)
    private int visits;

    @Column(nullable = false)
    private Instant createdAt;

    /** Updated on every /api/me — lets you see idle sessions in the table. */
    @Column(nullable = false)
    private Instant lastSeenAt;

    /**
     * The server-side deadline. The cookie has its own Max-Age, but that one is
     * only a polite request to the browser — this column is the one that is
     * actually enforced, because we own it.
     */
    @Column(nullable = false)
    private Instant expiresAt;

    // JPA requires an empty constructor.
    protected UserSession() {
    }

    public UserSession(String id, AppUser user, int visits, Instant now, Instant expiresAt) {
        this.id = id;
        this.username = user.getUsername();
        this.fullName = user.getFullName();
        this.email = user.getEmail();
        this.visits = visits;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    // ---- plain getters ----

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public int getVisits() {
        return visits;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
