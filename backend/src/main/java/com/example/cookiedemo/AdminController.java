package com.example.cookiedemo;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ==========================================================================
 *  THE ADMIN SIDE: SEEING BINDINGS, AND UNDOING THEM
 * ==========================================================================
 *  Device binding is a lock with no key on the user's side, and that is the
 *  intended design — a worker cannot move themselves to a new browser, or the
 *  control would mean nothing. So it needs someone who can, and these two
 *  endpoints are that someone:
 *
 *    GET  /api/admin/workers                        who is bound to what
 *    POST /api/admin/workers/{username}/reset-device unbind one of them
 *
 * --------------------------------------------------------------------------
 *  HOW AUTHORISATION WORKS HERE
 * --------------------------------------------------------------------------
 *  This project has no Spring Security — authentication is the hand-rolled
 *  session lookup in SessionService — so authorisation is hand-rolled to
 *  match, in requireAdmin() below. Two rules keep that honest:
 *
 *    1. Every method's FIRST statement is the check. Not a filter somewhere
 *       else that you have to remember to register, and not an annotation that
 *       silently does nothing if the wrong import is used.
 *
 *    2. The role is read from the DATABASE on every request, never from the
 *       session row and never from anything the client sent. A user demoted
 *       thirty seconds ago is not an admin thirty seconds later, and a client
 *       that POSTs {"role":"ADMIN"} at us is describing itself, not
 *       instructing us.
 *
 *  In an application with more than two endpoints this belongs in a filter or
 *  in Spring Security proper. At this size, three lines you can see is safer
 *  than a framework you have to trust you configured correctly.
 * ==========================================================================
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository users;
    private final SessionService sessions;
    private final DeviceBindingService devices;
    private final String cookieName;

    public AdminController(UserRepository users,
                           SessionService sessions,
                           DeviceBindingService devices,
                           @Value("${app.session.cookie-name:sid}") String cookieName) {
        this.users = users;
        this.sessions = sessions;
        this.devices = devices;
        this.cookieName = cookieName;
    }

    // ======================================================================
    //  LIST — every worker, and whether they are bound
    // ======================================================================
    @GetMapping("/workers")
    public ResponseEntity<?> listWorkers(HttpServletRequest request) {

        Optional<AppUser> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return denied();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (AppUser worker : users.findByRoleOrderByUsernameAsc(Role.WORKER)) {
            Map<String, Object> row = new HashMap<>();
            row.put("username", worker.getUsername());
            row.put("fullName", worker.getFullName());
            row.put("email", worker.getEmail());

            // A boolean, not the hash.
            //
            // The admin needs to know THAT a worker is bound so they know
            // whether a reset would do anything. They do not need the
            // fingerprint of the token to know that, and shipping it would put
            // a value on screen that only matters if it leaks. "Send the client
            // what it needs to draw the screen, and nothing else" applies to
            // admin screens too — arguably most of all, since those are the
            // ones that get screenshotted into support tickets.
            row.put("deviceRegistered", worker.hasRegisteredDevice());
            row.put("deviceRegisteredAt", worker.getDeviceRegisteredAt());
            rows.add(row);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("workers", rows);
        return ResponseEntity.ok(result);
    }

    // ======================================================================
    //  RESET — let this worker register a new browser
    // ======================================================================

    /**
     * Clears the worker's stored token. Their next successful login binds
     * whichever browser they use at that moment.
     *
     * POST, not GET or DELETE-by-link, because it changes state: a GET that
     * unbinds a worker can be fired by any image tag on any page an admin
     * visits. Combined with SameSite=Lax on the session cookie, requiring POST
     * is what keeps this endpoint out of reach of another site.
     *
     * Idempotent by design: resetting an already-unbound worker succeeds and
     * does nothing, so a double-clicked button is never an error.
     */
    @PostMapping("/workers/{username}/reset-device")
    public ResponseEntity<?> resetDevice(@PathVariable String username, HttpServletRequest request) {

        Optional<AppUser> admin = requireAdmin(request);
        if (admin.isEmpty()) {
            return denied();
        }

        Optional<AppUser> worker = users.findByUsername(username == null ? "" : username.trim());

        if (worker.isEmpty()) {
            return message(HttpStatus.NOT_FOUND, "No such user.");
        }

        if (worker.get().isAdmin()) {
            // Nothing to reset — admins were never bound. Saying so plainly
            // beats a confusing success message that appears to have done
            // something.
            return message(HttpStatus.BAD_REQUEST,
                    "Administrators are not bound to a workstation, so there is nothing to reset.");
        }

        devices.resetDevice(worker.get());

        return message(HttpStatus.OK,
                "Device registration cleared for " + worker.get().getUsername()
                        + ". Their next sign-in will register the browser they use.");
    }

    // ======================================================================
    //  THE GUARD
    // ======================================================================

    /**
     * Resolves the caller and returns them only if they are an admin.
     *
     * An empty Optional deliberately covers all three failure modes at once —
     * no session cookie, an expired session, a perfectly valid session
     * belonging to a worker — because the caller turns all three into the same
     * flat refusal. Distinguishing them in the response would tell someone
     * probing the endpoint whether their session is alive and merely
     * under-privileged, which is a free hint they do not need.
     */
    private Optional<AppUser> requireAdmin(HttpServletRequest request) {

        return sessions.lookup(CookieFactory.read(request, cookieName))
                .flatMap(session -> users.findByUsername(session.getUsername()))
                .filter(AppUser::isAdmin);
    }

    // ---- tiny utilities ----

    private static ResponseEntity<Map<String, Object>> denied() {
        return message(HttpStatus.FORBIDDEN, "Administrator access is required.");
    }

    private static ResponseEntity<Map<String, Object>> message(HttpStatus status, String text) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", text);
        return ResponseEntity.status(status).body(body);
    }
}
