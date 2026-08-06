package com.example.cookiedemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ==========================================================================
 *  THE CHICKEN-AND-EGG PROBLEM
 * ==========================================================================
 *  Only an admin can reset a worker's device. So a fresh deployment with no
 *  admin in it is a deployment where the first worker to clear their cookies
 *  is locked out permanently, with nobody able to help them.
 *
 *  There is exactly one safe way to create that first admin, and it is not
 *  through the app. /api/register hardcodes Role.WORKER precisely so that no
 *  request from the outside world can ever mint an administrator. That leaves
 *  server-side configuration, which is this class: an admin is created by
 *  whoever controls the environment variables, which is the same person who
 *  controls the database.
 *
 * --------------------------------------------------------------------------
 *  WHAT IT WILL AND WILL NOT DO
 * --------------------------------------------------------------------------
 *  It creates the account if it is missing, and promotes it if it exists as a
 *  worker. It does NOT overwrite the password of an account that already
 *  exists — if it did, the admin password would silently snap back to whatever
 *  is in the environment on every restart, quietly undoing any change made
 *  since, and anyone who ever saw the old deploy config would still be able to
 *  log in.
 *
 *  Promoting an existing worker also clears their device binding, because an
 *  admin has no business carrying one: leaving the row set would keep that
 *  browser claimed against a user who is no longer subject to the rule, and
 *  the workstation could never be reassigned.
 *
 *  Set app.bootstrap.admin.username to blank to switch the whole thing off.
 * ==========================================================================
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final String username;
    private final String password;
    private final String fullName;
    private final String email;

    public AdminBootstrap(UserRepository users,
                          @Value("${app.bootstrap.admin.username:admin}") String username,
                          @Value("${app.bootstrap.admin.password:admin}") String password,
                          @Value("${app.bootstrap.admin.full-name:Administrator}") String fullName,
                          @Value("${app.bootstrap.admin.email:admin@example.com}") String email) {
        this.users = users;
        this.username = username == null ? "" : username.trim();
        this.password = password;
        this.fullName = fullName;
        this.email = email;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {

        if (username.isEmpty()) {
            log.info("Admin bootstrap is disabled (app.bootstrap.admin.username is blank)");
            return;
        }

        Optional<AppUser> existing = users.findByUsername(username);

        if (existing.isEmpty()) {
            AppUser admin = new AppUser(username, password, fullName, email, Role.ADMIN);
            users.save(admin);
            log.info("Created the bootstrap administrator '{}'", username);
            warnAboutDefaults();
            return;
        }

        AppUser admin = existing.get();
        if (!admin.isAdmin()) {
            admin.setRole(Role.ADMIN);
            admin.clearDevice();
            users.save(admin);
            log.info("Promoted existing account '{}' to ADMIN", username);
        }
        // Already an admin: leave the row completely alone, password included.
    }

    /**
     * A deployment running on the built-in defaults has an account called
     * "admin" with the password "admin", reachable by anyone who finds the URL.
     * That is fine for a laptop and unacceptable anywhere else, so it says so
     * loudly rather than sitting quietly in a log nobody reads.
     */
    private void warnAboutDefaults() {
        if ("admin".equals(username) && "admin".equals(password)) {
            log.warn("");
            log.warn("  !!  The administrator account is using the built-in default password.");
            log.warn("  !!  Set APP_BOOTSTRAP_ADMIN_USERNAME and APP_BOOTSTRAP_ADMIN_PASSWORD");
            log.warn("  !!  before exposing this application to a network.");
            log.warn("");
        }
    }
}
