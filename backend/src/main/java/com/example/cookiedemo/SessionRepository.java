package com.example.cookiedemo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Database access for the SESSION table — same trick as {@link UserRepository}:
 * we only declare the method names and Spring writes the SQL at startup.
 *
 * Note the primary key type is String, not Long: a session's id IS the hashed
 * token, so there is no separate row number to look it up by.
 */
public interface SessionRepository extends JpaRepository<UserSession, String> {

    /** Used by the nightly cleanup: DELETE FROM user_session WHERE expires_at &lt; ? */
    long deleteByExpiresAtBefore(Instant cutoff);

    /** "Sign out everywhere" — kills every session this account has open. */
    long deleteByUsername(String username);

    List<UserSession> findByUsername(String username);
}
