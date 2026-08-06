package com.example.cookiedemo;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ==========================================================================
 *  THE PROOF
 * ==========================================================================
 *  Every claim made in AuthController's comment block is asserted here, so
 *  that "the cookie is secure now" is a thing the build checks rather than a
 *  thing we remember to be true.
 *
 *  Run it with:      mvnw.cmd test          (Windows)
 *                    ./mvnw test            (Mac / Linux)
 *
 *  These are integration tests, not unit tests: a real Spring context starts,
 *  the real controller runs, and a real (in-memory) database is written to.
 *  Only the network is faked — MockMvc feeds requests straight into the
 *  dispatcher, which is why the suite runs in seconds and needs no TLS
 *  certificate, no port, and no browser.
 *
 *  The one thing MockMvc CANNOT prove is that a browser actually honours the
 *  Secure and HttpOnly attributes. Nothing here starts a TLS connection. That
 *  half is verified by hand in the browser, and TESTING.md walks through it.
 * ==========================================================================
 */
@SpringBootTest
@AutoConfigureMockMvc
class SessionSecurityTest {

    private static final String COOKIE = "sid";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private SessionRepository sessions;

    /** Used once, to age a session past its deadline without waiting 30 days. */
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The fixture account is an ADMIN, and that is a deliberate choice rather
     * than an arbitrary one.
     *
     * Everything in this class is about the SESSION cookie: its attributes, its
     * rotation, its revocation, its expiry. Several of those tests sign in
     * twice from what MockMvc presents as a fresh browser each time — which is
     * exactly the pattern device binding exists to refuse. Leaving the fixture
     * as a worker would mean half the assertions below started failing on a
     * 403 that has nothing to do with what they are testing.
     *
     * Admins are exempt from device binding, so this keeps the session tests
     * measuring only sessions. The worker path — including that second login
     * being refused — is covered end to end in {@link DeviceBindingTest}.
     */
    @BeforeEach
    void freshStart() {
        sessions.deleteAll();
        users.deleteAll();
        users.save(new AppUser("tejas", "hunter2", "Tejas Jaiswal", "tejas@example.com", Role.ADMIN));
    }

    // ======================================================================
    //  1. THE COOKIE ATTRIBUTES
    // ======================================================================

    @Test
    @DisplayName("the Set-Cookie header carries every hardening attribute")
    void cookieIsHardened() throws Exception {

        String header = signIn().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(header)
                .as("the raw Set-Cookie header the browser receives")
                .contains("HttpOnly")          // JavaScript cannot read it
                .contains("Secure")            // never leaves over plain http
                .contains("SameSite=Lax")      // not attached to other sites' requests
                .contains("Path=/")            // sent for the whole app
                .contains("Max-Age=2592000");  // 30 days, in seconds

        // No Domain attribute -> host-only. A cookie scoped to a domain is also
        // handed to every subdomain of it, which is a much wider blast radius.
        assertThat(header).doesNotContain("Domain");
    }

    // ======================================================================
    //  2. THE COOKIE NO LONGER CONTAINS THE USER
    // ======================================================================

    @Test
    @DisplayName("the cookie value is an opaque id, not the user's details")
    void cookieCarriesNoPersonalData() throws Exception {

        String token = tokenFrom(signIn());

        // This is the assertion that would have failed before the change: the
        // old cookie literally read "tejas|Tejas Jaiswal|tejas@example.com|1".
        assertThat(token)
                .doesNotContain("tejas")
                .doesNotContain("Tejas")
                .doesNotContain("example.com")
                .doesNotContain("|")
                .doesNotContain("%7C");

        // 32 random bytes, Base64url-encoded without padding, is exactly 43 chars.
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("two sign-ins never produce the same id")
    void idsAreUnpredictable() throws Exception {
        assertThat(tokenFrom(signIn())).isNotEqualTo(tokenFrom(signIn()));
    }

    // ======================================================================
    //  3. THE DATABASE STORES A FINGERPRINT, NOT THE CREDENTIAL
    // ======================================================================

    @Test
    @DisplayName("the database holds SHA-256 of the cookie, never the cookie itself")
    void databaseStoresOnlyTheHash() throws Exception {

        String token = tokenFrom(signIn());

        // Someone who reads the whole session table still cannot sign in as us:
        assertThat(sessions.findById(token))
                .as("the raw cookie value must not be a key in the table")
                .isEmpty();

        assertThat(sessions.findById(SessionService.hash(token)))
                .as("the hash of it is")
                .isPresent();

        // And the payload really did move server-side.
        UserSession row = sessions.findById(SessionService.hash(token)).orElseThrow();
        assertThat(row.getUsername()).isEqualTo("tejas");
        assertThat(row.getFullName()).isEqualTo("Tejas Jaiswal");
        assertThat(row.getEmail()).isEqualTo("tejas@example.com");
        assertThat(row.getVisits()).isEqualTo(1);
    }

    // ======================================================================
    //  4. LOOKING THE SESSION UP AGAIN
    // ======================================================================

    @Test
    @DisplayName("/api/me answers from the database when the cookie is valid")
    void meResolvesTheSession() throws Exception {

        String token = tokenFrom(signIn());

        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.username").value("tejas"))
                .andExpect(jsonPath("$.fullName").value("Tejas Jaiswal"))
                .andExpect(jsonPath("$.email").value("tejas@example.com"));
    }

    @Test
    @DisplayName("no cookie means no session")
    void noCookieMeansAnonymous() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }

    // ======================================================================
    //  5. TAMPERING — the attack the old design was open to
    // ======================================================================

    @Test
    @DisplayName("an invented session id gets you nothing")
    void forgedIdIsRejected() throws Exception {
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    @DisplayName("hand-editing the old style of cookie no longer impersonates anyone")
    void handWrittenPayloadIsRejected() throws Exception {

        // Paste in exactly what used to work — a made-up identity with an
        // inflated visit count. Under the old code this was displayed verbatim.
        // Now it is just a string that hashes to nothing in the table.
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, "tejas|Someone Else|evil@example.com|999")))
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    @DisplayName("a failed sign-in issues no cookie and creates no session")
    void wrongPasswordIssuesNothing() throws Exception {

        MockHttpServletResponse response = mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tejas\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(sessions.count()).isZero();
    }

    // ======================================================================
    //  6. SESSION FIXATION — a new id every time privilege changes
    // ======================================================================

    @Test
    @DisplayName("signing in again rotates the id and kills the old one")
    void signingInRotatesTheSession() throws Exception {

        String first = tokenFrom(signIn());
        String second = tokenFrom(signInCarrying(first));

        assertThat(second)
                .as("a fresh id must be minted at sign-in, even though one was presented")
                .isNotEqualTo(first);

        // The id the browser arrived with is dead — this is what defeats
        // an attacker who planted a value in the cookie jar beforehand.
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, first)))
                .andExpect(jsonPath("$.found").value(false));

        // ...but the new one works.
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, second)))
                .andExpect(jsonPath("$.found").value(true));

        assertThat(sessions.count())
                .as("rotating must replace the row, not pile up a second one")
                .isEqualTo(1);
    }

    // ======================================================================
    //  6b. THE VISIT COUNTER — server-side only, and it counts return visits
    // ======================================================================

    @Test
    @DisplayName("the visit count is never sent to the browser")
    void visitCountStaysOnTheServer() throws Exception {

        String token = tokenFrom(signIn());

        // Not in the login response...
        assertThat(signIn().getContentAsString()).doesNotContain("visits");

        // ...and not in /api/me either.
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, token)))
                .andExpect(jsonPath("$.visits").doesNotExist());
    }

    @Test
    @DisplayName("refreshing the page counts as a visit, without signing in again")
    void returningVisitsAreCounted() throws Exception {

        String token = tokenFrom(signIn());
        assertThat(visitsOf("tejas")).as("the sign-in itself").isEqualTo(1);

        // Each of these is a page load: a refresh, a reopened browser, a new
        // tab. No password typed, just the cookie coming back.
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, token)));
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, token)));
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, token)));

        assertThat(visitsOf("tejas")).as("three return visits on top").isEqualTo(4);
    }

    @Test
    @DisplayName("a page load with no valid session counts nothing")
    void anonymousVisitsAreNotCounted() throws Exception {

        signIn();
        int before = visitsOf("tejas");

        mvc.perform(get("/api/me"));                                        // no cookie
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, "nonsense"))); // dead cookie

        assertThat(visitsOf("tejas")).isEqualTo(before);
    }

    @Test
    @DisplayName("the count survives signing out — that is why it lives on the account")
    void countSurvivesLogout() throws Exception {

        String token = tokenFrom(signIn());          // 1
        mvc.perform(post("/api/logout").cookie(new Cookie(COOKIE, token)));

        signIn();                                    // 2 — a brand-new session

        assertThat(visitsOf("tejas"))
                .as("a per-session counter would have restarted at 1 here")
                .isEqualTo(2);
    }

    // ======================================================================
    //  7. REVOCATION — the capability the cookie-only design did not have
    // ======================================================================

    @Test
    @DisplayName("/api/logout deletes the row on the server, so a copied cookie dies too")
    void logoutRevokesServerSide() throws Exception {

        String token = tokenFrom(signIn());

        MockHttpServletResponse response = mvc.perform(post("/api/logout")
                        .cookie(new Cookie(COOKIE, token)))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // The browser is told to drop it...
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        // ...but this is the part that actually matters:
        assertThat(sessions.count()).isZero();

        // Replay the exact cookie an attacker might have copied earlier. Under
        // the old design this still worked for the remaining 30 days.
        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, token)))
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    @DisplayName("/api/logout survives a cookie that points at nothing")
    void logoutToleratesAStaleCookie() throws Exception {

        // A real thing that happens: the database gets wiped (or the session
        // expires) while the browser still holds the cookie. Signing
        // out must clear the cookie, not blow up on a missing row.
        mvc.perform(post("/api/logout")
                        .cookie(new Cookie(COOKIE, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/logout"))   // and with no cookie at all
                .andExpect(status().isOk());
    }

    // ======================================================================
    //  8. EXPIRY IS ENFORCED BY US, NOT BY THE BROWSER
    // ======================================================================

    @Test
    @DisplayName("an expired session is rejected even though the browser still sends the cookie")
    void expiredSessionIsRejected() throws Exception {

        String token = tokenFrom(signIn());

        // Age the row past its deadline. A cookie's Max-Age is only a request
        // to the browser; a saved copy can be replayed long afterwards, so the
        // deadline that counts is the one in our own column.
        jdbc.update("UPDATE user_session SET expires_at = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        mvc.perform(get("/api/me").cookie(new Cookie(COOKIE, token)))
                .andExpect(jsonPath("$.found").value(false));

        assertThat(sessions.count())
                .as("reading an expired session should also clean it up")
                .isZero();
    }

    // ======================================================================
    //  helpers
    // ======================================================================

    /** Signs in as tejas with no cookie attached, and returns the raw response. */
    private MockHttpServletResponse signIn() throws Exception {
        return mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tejas\",\"password\":\"hunter2\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    /** Signs in while presenting an existing session cookie, the way a returning browser does. */
    private MockHttpServletResponse signInCarrying(String token) throws Exception {
        return mvc.perform(post("/api/login")
                        .cookie(new Cookie(COOKIE, token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tejas\",\"password\":\"hunter2\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    /** Reads the counter straight out of APP_USER — it is not exposed anywhere else. */
    private int visitsOf(String username) {
        return users.findByUsername(username).orElseThrow().getVisits();
    }

    private static String tokenFrom(MockHttpServletResponse response) {
        return tokenFrom(response.getHeader(HttpHeaders.SET_COOKIE));
    }

    /** "sid=VALUE; Path=/; Max-Age=...; Secure; HttpOnly; SameSite=Lax" -> VALUE */
    private static String tokenFrom(String setCookieHeader) {
        assertThat(setCookieHeader).as("a Set-Cookie header should have been sent").isNotNull();
        String firstPair = setCookieHeader.split(";", 2)[0];
        return firstPair.substring(firstPair.indexOf('=') + 1);
    }
}
