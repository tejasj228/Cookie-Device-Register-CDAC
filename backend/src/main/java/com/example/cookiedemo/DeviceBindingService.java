package com.example.cookiedemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * ==========================================================================
 *  DEVICE BINDING — one worker, one browser, forever
 * ==========================================================================
 *  The office rule this implements: a worker account may only ever be used
 *  from the browser it first signed in on. Not the first PC — the first
 *  BROWSER. Chrome and Firefox on the same machine have separate cookie jars,
 *  so they are separate workstations as far as this code is concerned. So is
 *  an incognito window, which throws its cookies away on close.
 *
 * --------------------------------------------------------------------------
 *  WHAT THE BINDING ACTUALLY IS
 * --------------------------------------------------------------------------
 *  A random UUID. That is the whole mechanism, and the shortness of that
 *  sentence is the point.
 *
 *  It is deliberately NOT a fingerprint of the machine — not the MAC address,
 *  not the IP, not a hardware serial, not a canvas/font/user-agent hash. Those
 *  all look more solid and are all worse here:
 *
 *    MAC address    a browser cannot read it, and a server only ever sees the
 *                   router's. Every desk behind one NAT looks identical.
 *    IP address     changes on a DHCP lease renewal, on wifi-to-ethernet, on
 *                   a VPN. Workers would be locked out by their own network.
 *    Machine ID     needs an agent installed on every PC. That is a fleet
 *                   management project, not a login feature.
 *    Fingerprinting collides between identical corporate builds — the exact
 *                   situation an office of cloned machines produces — and
 *                   silently breaks on every browser update.
 *
 *  A UUID we mint ourselves has none of those failure modes. It survives
 *  reboots, network changes and browser updates, it never collides, and it is
 *  meaningless to anyone who steals it without our database to match it
 *  against. The tradeoff is honest and stated up front: it is a COOKIE, so a
 *  user who clears their cookies loses their registration and needs an admin
 *  reset, and a user who deliberately copies the cookie file to another
 *  machine has moved their registration. This is a control for an internal
 *  office where nobody is doing that on purpose, not a DRM scheme.
 *
 * --------------------------------------------------------------------------
 *  THE DECISION TABLE
 * --------------------------------------------------------------------------
 *  Two independent questions, asked on every worker login:
 *
 *      does the browser present a device cookie?
 *      does this worker already have a device on file?
 *
 *  Which gives four cases, and the fourth splits in two:
 *
 *   cookie  worker  who owns the cookie   outcome
 *   ------  ------  -------------------   -----------------------------------
 *   no      no      —                     REGISTER  first-time binding
 *   no      yes     —                     DENIED    they are on a new machine
 *   yes     —       this worker           ALLOWED   the normal, boring path
 *   yes     —       another worker        DENIED    somebody else's desk
 *   yes     no      nobody (stale)        REGISTER  re-bind after a reset
 *   yes     yes     nobody (stale)        DENIED    their real device is elsewhere
 *
 *  Every branch below is one of those rows, in that order.
 * ==========================================================================
 */
@Service
public class DeviceBindingService {

    private static final Logger log = LoggerFactory.getLogger(DeviceBindingService.class);

    private final UserRepository users;
    private final SessionService sessions;

    public DeviceBindingService(UserRepository users, SessionService sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    // ======================================================================
    //  THE ANSWER SHAPE
    // ======================================================================

    public enum Outcome {

        /** Nothing to do. Either the browser matched, or the account is an admin. */
        ALLOWED,

        /** A brand-new token was minted and must be sent to the browser. */
        REGISTERED,

        /** Refuse the login. {@code message} explains which wall they hit. */
        DENIED
    }

    /**
     * What {@link #evaluate} hands back.
     *
     * @param outcome     what the caller must do
     * @param tokenToSend the raw UUID for the Set-Cookie header, present only on
     *                    REGISTERED. On every other outcome it is null, so there
     *                    is no way to accidentally hand a token to a browser we
     *                    just rejected.
     * @param code        a stable machine-readable reason, for the frontend
     * @param message     what the person at the keyboard should read
     */
    public record Decision(Outcome outcome, String tokenToSend, String code, String message) {

        public boolean isDenied() {
            return outcome == Outcome.DENIED;
        }

        static Decision allowed() {
            return new Decision(Outcome.ALLOWED, null, null, null);
        }

        static Decision registered(String token) {
            return new Decision(Outcome.REGISTERED, token, null, null);
        }

        static Decision denied(String code, String message) {
            return new Decision(Outcome.DENIED, null, code, message);
        }
    }

    /** Sent when the browser is already spoken for by a different account. */
    public static final String CODE_DEVICE_OWNED_BY_OTHER_USER = "DEVICE_OWNED_BY_OTHER_USER";

    /** Sent when the account is registered somewhere that is not this browser. */
    public static final String CODE_WORKER_BOUND_ELSEWHERE = "WORKER_BOUND_ELSEWHERE";

    // ======================================================================
    //  EVALUATE — called once per login, after the password has checked out
    // ======================================================================

    /**
     * Decides whether this (already authenticated) user may proceed from this
     * browser, and registers the browser if this is their first one.
     *
     * The order matters and is not negotiable: the caller MUST have verified
     * the password before getting here. Device binding is a second gate behind
     * authentication, never a substitute for it — a browser holding a valid
     * device cookie still has to type the right password, and a correct
     * password on the wrong browser still gets turned away.
     *
     * @param user            the account that just authenticated
     * @param presentedToken  the raw value of the device cookie the browser
     *                        sent, or null if it sent none
     */
    @Transactional
    public Decision evaluate(AppUser user, String presentedToken) {

        // ------------------------------------------------------------------
        //  ADMINS ARE EXEMPT, AND EXIT HERE
        // ------------------------------------------------------------------
        //  Not "admins pass the check" — admins are never asked the question.
        //  Nothing is read, nothing is written, and no device cookie is issued
        //  to them, so an admin signing in at a worker's desk leaves that
        //  desk's registration completely untouched.
        //
        //  This has to be true for the reset feature to be usable at all: the
        //  person who fixes a locked-out worker is standing at that worker's
        //  machine, which is by definition a machine bound to someone else.
        if (user.isAdmin()) {
            return Decision.allowed();
        }

        String token = normalise(presentedToken);
        Optional<AppUser> cookieOwner = token == null
                ? Optional.empty()
                : users.findByDeviceTokenHash(SessionService.hash(token));

        // ------------------------------------------------------------------
        //  CASE 1 & 2 — the browser has no device cookie
        // ------------------------------------------------------------------
        if (token == null) {

            if (!user.hasRegisteredDevice()) {
                return register(user);                     // first-time binding
            }

            // They are registered, but not here. Most often: a genuinely new
            // machine. Sometimes: the same machine after the cookies were
            // cleared. We cannot tell those apart and must not try — both are
            // "an admin needs to look at this".
            log.info("Denied login for worker {}: registered device, but this browser presented no token",
                    user.getUsername());
            return Decision.denied(CODE_WORKER_BOUND_ELSEWHERE,
                    "Your account is already registered to a different workstation. "
                            + "Ask an administrator to reset your device registration.");
        }

        // ------------------------------------------------------------------
        //  CASE 3 — the cookie belongs to the person signing in
        // ------------------------------------------------------------------
        // Compared by username rather than by row id: username carries a unique
        // constraint and can never be null, so the comparison is total.
        if (cookieOwner.isPresent() && cookieOwner.get().getUsername().equals(user.getUsername())) {
            return Decision.allowed();
        }

        // ------------------------------------------------------------------
        //  CASE 4 — the cookie belongs to somebody else
        // ------------------------------------------------------------------
        //  Note what we do NOT do here: name them. "This workstation belongs to
        //  priya.sharma" would turn the login form into a directory of who sits
        //  where, which is more than the person standing there needs to know.
        if (cookieOwner.isPresent()) {
            log.info("Denied login for worker {}: this workstation is bound to another account",
                    user.getUsername());
            return Decision.denied(CODE_DEVICE_OWNED_BY_OTHER_USER,
                    "This workstation is already registered to another user. "
                            + "Please sign in from your own workstation, or ask an administrator for help.");
        }

        // ------------------------------------------------------------------
        //  CASE 5 & 6 — the cookie matches nobody at all
        // ------------------------------------------------------------------
        //  A stale token. Two ways to get one, and they need opposite answers:
        //
        //    an admin just reset this worker  -> the browser still holds the
        //                                        old cookie; re-bind it and let
        //                                        them straight back in
        //    the worker is bound elsewhere    -> this stale cookie proves
        //                                        nothing about them; refuse
        //
        //  Which is why the branch is on the WORKER's state, not the cookie's.
        if (!user.hasRegisteredDevice()) {
            return register(user);
        }

        log.info("Denied login for worker {}: presented an unrecognised device token", user.getUsername());
        return Decision.denied(CODE_WORKER_BOUND_ELSEWHERE,
                "Your account is already registered to a different workstation. "
                        + "Ask an administrator to reset your device registration.");
    }

    // ======================================================================
    //  REGISTER — mint a UUID and pin the account to it
    // ======================================================================

    private Decision register(AppUser user) {

        // A version-4 UUID: 122 random bits from a cryptographically strong
        // source. Not a counter, not derived from the username, not derived
        // from anything about the machine — knowing one tells you nothing
        // about the next.
        String token = UUID.randomUUID().toString();

        // The raw token leaves this method inside the Decision and goes
        // straight into a Set-Cookie header. Only the hash is persisted.
        user.bindDevice(SessionService.hash(token), Instant.now());
        users.save(user);

        log.info("Registered a device for worker {}", user.getUsername());
        return Decision.registered(token);
    }

    // ======================================================================
    //  RESET — the admin escape hatch
    // ======================================================================

    /**
     * Unbinds a worker, so that their next successful login registers whichever
     * browser they use.
     *
     * Their open sessions are revoked at the same time, and that is deliberate
     * rather than tidy-mindedness. A reset happens because the worker is no
     * longer at the old machine — they moved desks, the PC was reimaged, the
     * laptop was stolen. Leaving a live session behind on the machine we just
     * unbound would mean the old browser stays signed in, which is precisely
     * the state the reset was called to end.
     *
     * @return false if there was no such worker
     */
    @Transactional
    public boolean resetDevice(AppUser worker) {

        if (worker == null || worker.isAdmin()) {
            return false;
        }

        worker.clearDevice();
        users.save(worker);

        long killed = sessions.revokeAllFor(worker.getUsername());
        log.info("Admin reset the device for worker {} ({} session(s) revoked)", worker.getUsername(), killed);
        return true;
    }

    // ---- tiny utility ----

    /** Treats null, "" and "   " as "no cookie was sent". */
    private static String normalise(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
