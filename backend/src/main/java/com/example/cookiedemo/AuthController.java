package com.example.cookiedemo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ==========================================================================
 *  THE WHOLE IDEA OF THIS PROJECT, IN ONE FILE
 * ==========================================================================
 *
 *  There are TWO places where data lives, and they do different jobs:
 *
 *  1. THE DATABASE (H2)  -> the permanent list of accounts, AND the list of
 *                           open sessions. Lives on the server, under our
 *                           control, editable only by us.
 *
 *  2. THE COOKIE         -> a small note the server asks the BROWSER to keep.
 *                           It used to hold the user's name, email and visit
 *                           count. It now holds ONE random number and nothing
 *                           else — a claim check, not the coat.
 *
 *  Flow:
 *    register -> a row is saved into APP_USER
 *    login    -> we check APP_USER, mint a session row, and send its id as a
 *                hardened cookie (HttpOnly + Secure + SameSite, over HTTPS)
 *    reopen   -> the browser sends the id back, we look the row up, and the
 *                user is STILL SIGNED IN — for up to 30 days, across refreshes
 *                and browser restarts. Their details come from the DATABASE.
 *    logout   -> we DELETE the session row (so every copy of that cookie dies)
 *                and tell the browser to drop the cookie too
 *
 * ==========================================================================
 *  WHAT CHANGED, AND WHY EACH PIECE MATTERS
 * ==========================================================================
 *
 *  BEFORE                                 NOW
 *  ------------------------------------   ------------------------------------
 *  Cookie held name/email/visit count     Cookie holds 256 random bits
 *    -> user could read their own data      -> the value means nothing to
 *       and, worse, edit it                    anyone who does not have our DB
 *
 *  No HttpOnly                            HttpOnly
 *    -> any injected <script> could         -> document.cookie cannot see it,
 *       read document.cookie                  so an XSS bug cannot steal it
 *
 *  No Secure                              Secure + the whole app on HTTPS
 *    -> sent in clear text over http,       -> the browser refuses to send it
 *       readable on any shared wifi            over a plain http connection
 *
 *  No SameSite                            SameSite=Lax
 *    -> other sites' requests carried       -> it is not attached to requests
 *       it along (CSRF)                        started by another site
 *
 *  Nothing on the server                  A row we can delete
 *    -> "log out" was a suggestion          -> revoking is instant and total,
 *       to the browser                         even for a stolen copy
 *
 * ==========================================================================
 *  THE SECOND COOKIE: DEVICE BINDING
 * ==========================================================================
 *  There are now TWO cookies, and keeping them straight is the main thing to
 *  understand about this file:
 *
 *    sid   WHO is signed in right now. Rotated on every login, deleted on
 *          sign-out. Answers "may I skip the password?"
 *
 *    did   WHICH workstation this browser is. Written once, never rotated,
 *          and deliberately NOT deleted on sign-out. Answers "is this person
 *          allowed to sign in from here at all?"
 *
 *  They are checked at different moments and for different reasons. The
 *  session cookie is checked on every request. The device cookie is checked
 *  only at login, because that is the only moment the answer can change — and
 *  it is checked AFTER the password, never instead of it.
 *
 *  All the policy lives in {@link DeviceBindingService}; this controller only
 *  reads the cookie, asks, and acts on the answer.
 * ==========================================================================
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final UserRepository users;
    private final SessionService sessions;
    private final DeviceBindingService devices;
    private final CookieFactory cookies;

    /**
     * The name shown in DevTools > Application > Cookies.
     *
     * Short and boring on purpose. The old name, "userInfo", advertised that
     * there was information inside worth looking at; "sid" says nothing.
     *
     * Hardening option: rename this to "__Host-sid". Browsers give any cookie
     * starting with "__Host-" special treatment — they refuse to store it
     * unless it is Secure, Path=/ and has no Domain, and they forbid a
     * subdomain from overwriting it. We already meet every one of those
     * conditions. It is left off by default only because the prefix makes the
     * cookie silently vanish if you ever flip app.session.cookie-secure=false
     * for plain-HTTP debugging, which is a confusing thing to run into.
     */
    private final String cookieName;

    /** How long the browser is asked to keep the session cookie. */
    private final Duration cookieMaxAge;

    /** The device cookie's name. "did" — device id. Says as little as "sid" does. */
    private final String deviceCookieName;

    /**
     * How long the browser is asked to keep the device cookie.
     *
     * Ten years, because "permanently bound" is the requirement and a cookie
     * has no way to say "never expires" — the longest Max-Age you can express
     * is the closest thing to forever the format offers. Browsers cap this at
     * around 400 days in practice (Chrome does, per RFC 6265bis), which is
     * exactly why the cookie is re-sent with a fresh deadline on every
     * successful login: an account in daily use never gets near the cap.
     */
    private final Duration deviceCookieMaxAge;

    public AuthController(UserRepository users,
                          SessionService sessions,
                          DeviceBindingService devices,
                          CookieFactory cookies,
                          @Value("${app.session.cookie-name:sid}") String cookieName,
                          @Value("${app.session.ttl-minutes:43200}") long ttlMinutes,
                          @Value("${app.device.cookie-name:did}") String deviceCookieName,
                          @Value("${app.device.cookie-max-age-days:3650}") long deviceCookieDays) {
        this.users = users;
        this.sessions = sessions;
        this.devices = devices;
        this.cookies = cookies;
        this.cookieName = cookieName;
        this.cookieMaxAge = Duration.ofMinutes(ttlMinutes);
        this.deviceCookieName = deviceCookieName;
        this.deviceCookieMaxAge = Duration.ofDays(deviceCookieDays);
    }

    // ======================================================================
    //  1. REGISTER  ->  saves a new row in APP_USER
    // ======================================================================
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {

        String username = trim(body.get("username"));
        String password = trim(body.get("password"));
        String fullName = trim(body.get("fullName"));
        String email = trim(body.get("email"));

        if (username.isEmpty() || password.isEmpty() || fullName.isEmpty() || email.isEmpty()) {
            return error("Please fill in every field.");
        }

        if (users.existsByUsername(username)) {
            return error("That username is already taken. Try signing in instead.");
        }

        // Always a WORKER. The role is never read off the request body, because
        // a self-service form that accepts {"role":"ADMIN"} is not a
        // registration form, it is a privilege escalation endpoint. Admins are
        // made by AdminBootstrap from server-side configuration only.
        users.save(new AppUser(username, password, fullName, email, Role.WORKER));

        Map<String, Object> result = new HashMap<>();
        result.put("message", "Account created. You can sign in now.");
        return ResponseEntity.ok(result);
    }

    // ======================================================================
    //  2. LOGIN  ->  checks APP_USER, mints a SESSION, sends its id as a cookie
    // ======================================================================
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {

        String username = trim(body.get("username"));
        String password = trim(body.get("password"));

        // Look the user up in the database.
        Optional<AppUser> found = users.findByUsername(username);

        if (found.isEmpty() || !found.get().getPassword().equals(password)) {
            return error("Wrong username or password.");
        }

        AppUser user = found.get();

        // ==================================================================
        //  THE DEVICE GATE
        // ==================================================================
        //  Second, and only second. The password has already been checked, so
        //  by this line we know WHO is asking; this decides whether they may
        //  ask from HERE.
        //
        //  It sits above everything that has an effect — before the visit
        //  count, before the old session is revoked, before a new one is
        //  minted — so that a refusal leaves the server exactly as it found
        //  it. A worker who is turned away has not had their visit counted and
        //  has not been signed out of the machine they are legitimately still
        //  signed in on somewhere else.
        String presentedDeviceToken = CookieFactory.read(request, deviceCookieName);
        DeviceBindingService.Decision decision = devices.evaluate(user, presentedDeviceToken);

        if (decision.isDenied()) {
            return forbid(decision.code(), decision.message());
        }

        // ---- Count the visit on the ACCOUNT ----
        //
        // The counter used to live in the cookie, which meant anyone could set
        // it to 900 by hand. It now lives on a row that only the server writes,
        // and is never sent to the browser at all.
        int visits = user.recordVisit();
        users.save(user);

        String oldToken = readToken(request);

        // ---- Always issue a BRAND-NEW id, never reuse the one we were handed ----
        //
        // This is the defence against session fixation. The attack: someone
        // gets a value into your browser's cookie jar BEFORE you sign in (via a
        // subdomain, a stray Set-Cookie, a link with a session in it), waits
        // for you to log in, and then uses that same value themselves — it is
        // now attached to your account. Minting a fresh id at the moment
        // privilege changes makes whatever they planted worthless.
        sessions.revoke(oldToken);

        SessionService.IssuedSession issued = sessions.mint(user, visits);
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.build(cookieName, issued.token(), cookieMaxAge).toString());

        // ---- The device cookie ----
        //
        // Two cases, and an admin matches neither, so an admin never receives
        // this cookie and never has one refreshed:
        //
        //   REGISTERED  a token was just minted. This is the one login in the
        //               account's life that writes it.
        //
        //   ALLOWED     the worker's browser already had the right token. We
        //               send the SAME value back, purely to reset Max-Age.
        //               Without this the registration would quietly expire out
        //               from under a long-serving employee — browsers cap
        //               cookie lifetime at around 400 days no matter what we
        //               ask for — and they would arrive one morning locked out
        //               of a machine they had used for years.
        if (decision.outcome() == DeviceBindingService.Outcome.REGISTERED) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                    cookies.build(deviceCookieName, decision.tokenToSend(), deviceCookieMaxAge).toString());

        } else if (!user.isAdmin() && presentedDeviceToken != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                    cookies.build(deviceCookieName, presentedDeviceToken, deviceCookieMaxAge).toString());
        }

        UserSession session = issued.session();
        return ResponseEntity.ok(info(session, user));
    }

    // ======================================================================
    //  3. ME  ->  looks the session up. Called by React every time the page opens.
    //
    //  This is what keeps you signed in. React calls it before it draws
    //  anything: if a live session comes back, it goes straight to the account
    //  screen; if not, you get the sign-in form. Refreshing the page, closing
    //  the tab, or restarting the machine all run through here.
    // ======================================================================
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {

        Optional<UserSession> session = sessions.lookup(readToken(request));

        Map<String, Object> result = new HashMap<>();
        if (session.isEmpty()) {
            // No cookie, or a cookie pointing at nothing -> a brand new visitor.
            result.put("found", false);
            return ResponseEntity.ok(result);
        }

        // ---- This return visit counts ----
        //
        // Reaching here means a live session was resolved without anybody
        // typing a password: a refresh, a reopened browser, a new tab. That is
        // exactly what the counter is for, so it goes up here as well as at
        // sign-in. The number stays on the server — it is not in the response.
        //
        // Note that the device cookie is NOT re-checked here, and that is on
        // purpose. Device binding is a rule about who may START a session; once
        // a session exists it is the session that authorises the request. Every
        // route to a session already went through the gate.
        Optional<AppUser> user = users.findByUsername(session.get().getUsername());
        user.ifPresent(u -> {
            u.recordVisit();
            users.save(u);
        });

        // Notice where this data comes from: the DATABASE. The browser sent us
        // an id and nothing more. There is no longer any value in the request
        // that we display back to the user, which is why there is no longer
        // any parsing or validating to do here.
        result.putAll(info(session.get(), user.orElse(null)));
        result.put("found", true);
        return ResponseEntity.ok(result);
    }

    // ======================================================================
    //  4. LOGOUT  ->  a real, server-side logout.
    //
    //  Two steps, and the order matters. Deleting the row is the one that
    //  counts; clearing the cookie is just tidying up the browser afterwards.
    //  Doing only the second is what the old version did, and it meant a
    //  copied cookie stayed valid for the full 30 days.
    //
    //  This endpoint used to be called /api/forget, back when signing out was
    //  purely a screen change in React and the cookie deliberately survived it.
    //  Now that a live session actually keeps you signed in across a refresh,
    //  "sign out" has to mean something on the server, and this is it.
    // ======================================================================
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {

        sessions.revoke(readToken(request));                    // 1. the truth
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookies.expire(cookieName).toString());        // 2. the tidy-up

        // The device cookie is deliberately left alone.
        //
        // It is easy to read "sign out" as "forget everything about me" and
        // clear both. That would be a bug: the device cookie is not a
        // credential for an account, it is the identity of the WORKSTATION,
        // and the workstation has not gone anywhere. Clearing it would unbind
        // every worker the moment they signed out, and the next morning
        // everyone would be locked out of their own desk needing an admin.
        // Only an admin reset removes a binding.

        Map<String, Object> result = new HashMap<>();
        result.put("message", "Signed out. Session ended and cookie erased.");
        return ResponseEntity.ok(result);
    }

    // ======================================================================
    //  COOKIE HELPERS
    // ======================================================================

    /*
     * Both cookies are built by CookieFactory, which owns the attribute list —
     * HttpOnly, Secure, SameSite, Path=/ and no Domain — and the explanation of
     * why each one is there. Two cookies with two copies of that list would be
     * two chances to forget an attribute on the one that matters most.
     */

    /** Pulls the session cookie's raw value out of the incoming request, or null. */
    private String readToken(HttpServletRequest request) {
        return CookieFactory.read(request, cookieName);
    }

    // ---- tiny utilities ----

    /**
     * The shape of the user data we send back to React — read off the session row.
     *
     * Note what is NOT here: the visit counter. It is deliberately server-side
     * only, so there is nothing on screen and nothing in the JSON. If you want
     * to watch it climb, look at APP_USER.VISITS in the H2 console.
     *
     * The general habit is worth keeping: send the client what it needs to draw
     * the screen, and nothing else. Every extra field is one more thing that
     * leaks, one more thing to keep in sync, and one more thing somebody might
     * be tempted to trust on the way back in.
     */
    private static Map<String, Object> info(UserSession session, AppUser user) {
        Map<String, Object> map = new HashMap<>();
        map.put("username", session.getUsername());
        map.put("fullName", session.getFullName());
        map.put("email", session.getEmail());

        // The role IS sent, unlike the visit count, because the frontend has to
        // know whether to draw the admin panel. Sending it is safe as long as
        // nothing is ever authorised by it: every admin endpoint re-derives the
        // role from the database on its own. This field decides what a screen
        // looks like, never what a request is allowed to do.
        //
        // Read live off the account rather than off the session snapshot, so
        // that demoting someone takes effect on their next page load instead of
        // whenever their 30-day session happens to end. Null only if the
        // account was deleted mid-session, in which case WORKER is the safe
        // thing to assume.
        map.put("role", (user == null ? Role.WORKER : user.getRole()).name());
        return map;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 403, with a machine-readable code alongside the human sentence.
     *
     * 403 and not 401: 401 means "I do not know who you are, try again with
     * credentials", which would be actively misleading here. The credentials
     * were perfect. The answer is "I know exactly who you are, and the answer
     * is still no" — which is what 403 means.
     *
     * The code exists so the frontend can style the two device refusals
     * differently from a typo in a password without pattern-matching on
     * English prose that a translator will eventually change.
     */
    private static ResponseEntity<Map<String, Object>> forbid(String code, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
