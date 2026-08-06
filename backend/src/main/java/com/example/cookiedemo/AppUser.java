package com.example.cookiedemo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row in the database = one registered user.
 *
 * @Entity tells Spring: "turn this Java class into a database table".
 * The table will be called APP_USER and will have 5 columns.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    /** Auto-generated row number: 1, 2, 3... */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login name. unique = the database refuses two users with the same name. */
    @Column(unique = true, nullable = false)
    private String username;

    /** Password. NOTE: stored as plain text to keep this demo readable (see README). */
    @Column(nullable = false)
    private String password;

    /** Display name, e.g. "Tejas Jaiswal". Cached in the cookie. */
    @Column(nullable = false)
    private String fullName;

    /** Email address. */
    @Column(nullable = false)
    private String email;

    /**
     * How many times we have seen this account.
     *
     * It goes up on a fresh sign-in AND on every returning page load — refresh
     * the page or close the browser and come back, and the number climbs,
     * because /api/me resolved a live session and that counts as a visit.
     *
     * It lives HERE, on the account, rather than on the session row, for two
     * reasons. Signing out now revokes the session, so a per-session counter
     * would restart at 1 every time and measure nothing. And a per-account
     * counter is the only one that survives the session rotation that happens
     * on every login.
     *
     * Deliberately NOT sent to the browser. Nothing in the API response or on
     * screen shows it — look at the APP_USER table in the H2 console.
     *
     * The columnDefinition is there for one practical reason: ddl-auto=update
     * has to ADD this column to a table that already has rows in it, and a
     * NOT NULL column can only be added to a populated table if it comes with
     * a DEFAULT. Without it, the app refuses to start against an existing
     * database.
     */
    @Column(nullable = false, columnDefinition = "INT DEFAULT 0 NOT NULL")
    private int visits;

    /**
     * WORKER or ADMIN. See {@link Role} for what the difference buys you.
     *
     * Same DEFAULT trick as {@code visits}, and for the same reason: ddl-auto
     * has to add a NOT NULL column to a table that already has rows in it, and
     * that is only legal if the column comes with a default. Anyone who
     * registered before this feature existed becomes a WORKER, which is the
     * safe direction to be wrong in.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "VARCHAR(16) DEFAULT 'WORKER' NOT NULL")
    private Role role = Role.WORKER;

    /**
     * SHA-256 of the device token this worker's browser holds, hex-encoded, or
     * null if they have not registered a browser yet.
     *
     * ------------------------------------------------------------------------
     *  Why the HASH and not the token
     * ------------------------------------------------------------------------
     *  Exactly the reasoning from {@link UserSession}: the raw token is a live
     *  credential. Anyone holding it can walk up to a fresh browser, paste it
     *  into a cookie, and that browser now passes as this worker's registered
     *  workstation. A leaked backup or a screenshot of the table would hand
     *  that over. Storing only the fingerprint means the column is useless to
     *  read and still perfectly good to compare against.
     *
     *  Unique, because a browser belongs to at most one worker — the database
     *  itself refuses to let two workers claim the same machine, so that rule
     *  survives even a bug in the service layer. NULL is exempt from a unique
     *  constraint in both H2 and Postgres, so any number of workers may sit
     *  unregistered at the same time.
     * ------------------------------------------------------------------------
     */
    @Column(unique = true, length = 64)
    private String deviceTokenHash;

    /** When the current browser was bound. Null whenever {@code deviceTokenHash} is null. */
    @Column
    private Instant deviceRegisteredAt;

    // JPA requires an empty constructor.
    public AppUser() {
    }

    public AppUser(String username, String password, String fullName, String email) {
        this(username, password, fullName, email, Role.WORKER);
    }

    public AppUser(String username, String password, String fullName, String email, Role role) {
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
    }

    // ---- plain getters and setters ----

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public int getVisits() {
        return visits;
    }

    /** Called on every sign-in and on every returning page load. */
    public int recordVisit() {
        return ++visits;
    }

    // ---- role ----

    public Role getRole() {
        return role == null ? Role.WORKER : role;
    }

    public void setRole(Role role) {
        this.role = role == null ? Role.WORKER : role;
    }

    public boolean isAdmin() {
        return getRole().isAdmin();
    }

    // ---- device binding ----

    public String getDeviceTokenHash() {
        return deviceTokenHash;
    }

    public Instant getDeviceRegisteredAt() {
        return deviceRegisteredAt;
    }

    /** True once this worker is pinned to a browser. */
    public boolean hasRegisteredDevice() {
        return deviceTokenHash != null && !deviceTokenHash.isBlank();
    }

    /**
     * Pins this account to a browser. The caller passes the HASH, never the
     * token — keeping the raw value out of the entity entirely is what stops
     * it being written to the table by accident.
     */
    public void bindDevice(String tokenHash, Instant when) {
        this.deviceTokenHash = tokenHash;
        this.deviceRegisteredAt = when;
    }

    /** The admin reset. Both columns go together, so "bound" is never half-true. */
    public void clearDevice() {
        this.deviceTokenHash = null;
        this.deviceRegisteredAt = null;
    }
}
