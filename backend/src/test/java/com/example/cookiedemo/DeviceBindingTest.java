package com.example.cookiedemo;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ==========================================================================
 *  THE DECISION TABLE, AS TESTS
 * ==========================================================================
 *  DeviceBindingService documents six cases. Each one has a test here, named
 *  after the row it covers, so the table cannot drift away from the code
 *  without the build noticing.
 *
 *  Everything below drives the REAL endpoints through MockMvc rather than
 *  calling the service directly. That is deliberate: the interesting bugs in a
 *  feature like this are not in the branch logic, they are in the wiring —
 *  checking the device before the password, setting the cookie on the wrong
 *  outcome, letting a rejected login still count a visit. A unit test of the
 *  service would pass through every one of those.
 *
 *  Run it with:      mvnw.cmd test          (Windows)
 *                    ./mvnw test            (Mac / Linux)
 * ==========================================================================
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceBindingTest {

    private static final String SESSION_COOKIE = "sid";
    private static final String DEVICE_COOKIE = "did";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private SessionRepository sessions;

    @BeforeEach
    void freshStart() {
        sessions.deleteAll();
        users.deleteAll();
        users.save(new AppUser("asha", "pw-asha", "Asha Rao", "asha@example.com", Role.WORKER));
        users.save(new AppUser("bilal", "pw-bilal", "Bilal Khan", "bilal@example.com", Role.WORKER));
        users.save(new AppUser("root", "pw-root", "Ops Admin", "root@example.com", Role.ADMIN));
    }

    // ======================================================================
    //  ROW 1 — no cookie, no stored device  ->  REGISTER
    // ======================================================================

    @Nested
    @DisplayName("first-time registration")
    class FirstLogin {

        @Test
        @DisplayName("a worker's first login mints a device token and sends it as a cookie")
        void bindsTheBrowser() throws Exception {

            MockHttpServletResponse response = login("asha", "pw-asha");

            String token = deviceTokenFrom(response);
            assertThat(token)
                    .as("a device cookie should have been issued")
                    .isNotNull()
                    // A UUID and nothing else: 8-4-4-4-12 lowercase hex.
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

            assertThat(users.findByUsername("asha").orElseThrow().hasRegisteredDevice()).isTrue();
        }

        @Test
        @DisplayName("the database stores the hash of the device token, never the token")
        void storesOnlyTheHash() throws Exception {

            String token = deviceTokenFrom(login("asha", "pw-asha"));

            // Whoever reads the APP_USER table cannot turn what they find there
            // back into a working device cookie.
            assertThat(users.findByDeviceTokenHash(token))
                    .as("the raw token must not appear in the column")
                    .isEmpty();

            assertThat(users.findByDeviceTokenHash(SessionService.hash(token)))
                    .as("the hash of it must")
                    .isPresent()
                    .get()
                    .extracting(AppUser::getUsername)
                    .isEqualTo("asha");
        }

        @Test
        @DisplayName("the device cookie is hardened and persistent")
        void deviceCookieIsHardened() throws Exception {

            String header = deviceCookieHeaderFrom(login("asha", "pw-asha"));

            assertThat(header)
                    .contains("HttpOnly")           // no script can read or overwrite it
                    .contains("Secure")             // never leaves over plain http
                    .contains("SameSite=Lax")       // not attached to other sites' requests
                    .contains("Path=/")
                    .doesNotContain("Domain");      // host-only

            // Persistent, not a session cookie: it has to survive closing the
            // browser, or the binding would be forgotten every evening.
            assertThat(header).contains("Max-Age=315360000");   // 3650 days
        }

        @Test
        @DisplayName("two workers registering never receive the same token")
        void tokensAreUnique() throws Exception {
            assertThat(deviceTokenFrom(login("asha", "pw-asha")))
                    .isNotEqualTo(deviceTokenFrom(login("bilal", "pw-bilal")));
        }
    }

    // ======================================================================
    //  ROW 3 — cookie present, owned by this worker  ->  ALLOW
    // ======================================================================

    @Nested
    @DisplayName("the registered browser")
    class SameBrowser {

        @Test
        @DisplayName("the worker can sign in again from the browser they registered")
        void allowsTheBoundBrowser() throws Exception {

            String device = deviceTokenFrom(login("asha", "pw-asha"));

            mvc.perform(post("/api/login")
                            .cookie(new Cookie(DEVICE_COOKIE, device))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"asha\",\"password\":\"pw-asha\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("asha"));
        }

        @Test
        @DisplayName("signing in again refreshes the cookie's deadline without changing its value")
        void refreshesRatherThanRotates() throws Exception {

            String first = deviceTokenFrom(login("asha", "pw-asha"));

            MockHttpServletResponse again = mvc.perform(post("/api/login")
                            .cookie(new Cookie(DEVICE_COOKIE, first))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"asha\",\"password\":\"pw-asha\"}"))
                    .andReturn().getResponse();

            // Unlike the session cookie, which is rotated on every login to
            // defeat fixation, the device cookie must NOT change — it is the
            // identity of the workstation, and rotating it would mean the
            // stored hash and the browser drifted apart on any failed write.
            assertThat(deviceTokenFrom(again))
                    .as("the same token, re-sent with a fresh Max-Age")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("the right browser still needs the right password")
        void deviceIsNotACredential() throws Exception {

            String device = deviceTokenFrom(login("asha", "pw-asha"));

            // The gate is a second lock, not a replacement for the first.
            mvc.perform(post("/api/login")
                            .cookie(new Cookie(DEVICE_COOKIE, device))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"asha\",\"password\":\"wrong\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ======================================================================
    //  ROW 4 — cookie present, owned by someone else  ->  DENY
    // ======================================================================

    @Test
    @DisplayName("a different worker cannot sign in on a workstation that is already registered")
    void rejectsAnotherWorkerOnABoundWorkstation() throws Exception {

        String ashasDesk = deviceTokenFrom(login("asha", "pw-asha"));

        mvc.perform(post("/api/login")
                        .cookie(new Cookie(DEVICE_COOKIE, ashasDesk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bilal\",\"password\":\"pw-bilal\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(DeviceBindingService.CODE_DEVICE_OWNED_BY_OTHER_USER))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("another user")));

        // And the refusal did not quietly leak whose desk it is.
        assertThat(users.findByUsername("bilal").orElseThrow().hasRegisteredDevice())
                .as("a rejected login must not bind the worker to anything")
                .isFalse();
    }

    // ======================================================================
    //  ROW 2 — no cookie, worker already bound  ->  DENY
    // ======================================================================

    @Test
    @DisplayName("a bound worker cannot sign in from a browser with no device cookie")
    void rejectsABoundWorkerOnAFreshBrowser() throws Exception {

        login("asha", "pw-asha");   // binds her first browser

        // A different PC, or the same one after Clear Browsing Data. Either
        // way, no cookie arrives.
        mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"asha\",\"password\":\"pw-asha\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(DeviceBindingService.CODE_WORKER_BOUND_ELSEWHERE))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("administrator")));
    }

    // ======================================================================
    //  ROW 6 — stale cookie, worker bound elsewhere  ->  DENY
    // ======================================================================

    @Test
    @DisplayName("a made-up device cookie does not get a bound worker in")
    void rejectsAForgedDeviceToken() throws Exception {

        login("asha", "pw-asha");

        mvc.perform(post("/api/login")
                        .cookie(new Cookie(DEVICE_COOKIE, "11111111-2222-3333-4444-555555555555"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"asha\",\"password\":\"pw-asha\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(DeviceBindingService.CODE_WORKER_BOUND_ELSEWHERE));
    }

    // ======================================================================
    //  A REFUSAL MUST LEAVE NO TRACE
    // ======================================================================

    @Test
    @DisplayName("a device refusal issues no session, no cookie, and does not count a visit")
    void refusalHasNoSideEffects() throws Exception {

        String ashasDesk = deviceTokenFrom(login("asha", "pw-asha"));
        long sessionsBefore = sessions.count();
        int bilalVisitsBefore = users.findByUsername("bilal").orElseThrow().getVisits();

        MockHttpServletResponse response = mvc.perform(post("/api/login")
                        .cookie(new Cookie(DEVICE_COOKIE, ashasDesk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bilal\",\"password\":\"pw-bilal\"}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse();

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .as("a rejected login hands out nothing at all")
                .isEmpty();

        assertThat(sessions.count()).isEqualTo(sessionsBefore);
        assertThat(users.findByUsername("bilal").orElseThrow().getVisits()).isEqualTo(bilalVisitsBefore);
    }

    @Test
    @DisplayName("a device refusal does not disturb the session already on that browser")
    void refusalLeavesTheExistingSessionAlone() throws Exception {

        MockHttpServletResponse ashaLogin = login("asha", "pw-asha");
        String ashasSession = sessionTokenFrom(ashaLogin);
        String ashasDesk = deviceTokenFrom(ashaLogin);

        // Bilal tries Asha's desk while she is still signed in there. Login
        // normally revokes whatever session the browser arrived with — that is
        // the fixation defence — so the refusal has to happen first, or Bilal
        // could sign Asha out just by failing.
        mvc.perform(post("/api/login")
                        .cookie(new Cookie(SESSION_COOKIE, ashasSession))
                        .cookie(new Cookie(DEVICE_COOKIE, ashasDesk))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bilal\",\"password\":\"pw-bilal\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/me").cookie(new Cookie(SESSION_COOKIE, ashasSession)))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.username").value("asha"));
    }

    // ======================================================================
    //  ADMINS ARE OUTSIDE THE WHOLE MECHANISM
    // ======================================================================

    @Nested
    @DisplayName("admins")
    class Admins {

        @Test
        @DisplayName("an admin logging in is never issued a device cookie")
        void adminGetsNoDeviceCookie() throws Exception {

            MockHttpServletResponse response = login("root", "pw-root");

            assertThat(deviceTokenFrom(response)).isNull();
            assertThat(users.findByUsername("root").orElseThrow().hasRegisteredDevice()).isFalse();
        }

        @Test
        @DisplayName("an admin can sign in on a workstation registered to a worker")
        void adminIgnoresSomeoneElsesBinding() throws Exception {

            String ashasDesk = deviceTokenFrom(login("asha", "pw-asha"));

            // This is the case that has to work, or the reset feature is
            // unusable: the admin fixing a locked-out worker is standing at
            // that worker's machine.
            mvc.perform(post("/api/login")
                            .cookie(new Cookie(DEVICE_COOKIE, ashasDesk))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"root\",\"password\":\"pw-root\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("ADMIN"));

            // ...and Asha's desk is still Asha's afterwards.
            assertThat(users.findByDeviceTokenHash(SessionService.hash(ashasDesk)))
                    .get()
                    .extracting(AppUser::getUsername)
                    .isEqualTo("asha");
        }

        @Test
        @DisplayName("an admin can sign in from any number of browsers at once")
        void adminIsNotPinned() throws Exception {
            login("root", "pw-root");
            login("root", "pw-root");
            mvc.perform(post("/api/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"root\",\"password\":\"pw-root\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("/api/me reports the role, so the frontend knows which screen to draw")
        void meCarriesTheRole() throws Exception {

            String workerSession = sessionTokenFrom(login("asha", "pw-asha"));
            mvc.perform(get("/api/me").cookie(new Cookie(SESSION_COOKIE, workerSession)))
                    .andExpect(jsonPath("$.role").value("WORKER"));

            String adminSession = sessionTokenFrom(login("root", "pw-root"));
            mvc.perform(get("/api/me").cookie(new Cookie(SESSION_COOKIE, adminSession)))
                    .andExpect(jsonPath("$.role").value("ADMIN"));
        }

        @Test
        @DisplayName("registering through the public form can never create an admin")
        void registrationIgnoresAnyRoleInTheBody() throws Exception {

            mvc.perform(post("/api/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"sneaky\",\"password\":\"pw\",\"fullName\":\"S\","
                                    + "\"email\":\"s@example.com\",\"role\":\"ADMIN\"}"))
                    .andExpect(status().isOk());

            assertThat(users.findByUsername("sneaky").orElseThrow().getRole()).isEqualTo(Role.WORKER);
        }
    }

    // ======================================================================
    //  ROW 5 — the reset, and the re-registration it enables
    // ======================================================================

    @Nested
    @DisplayName("admin reset")
    class Reset {

        @Test
        @DisplayName("only an admin may reset a device")
        void guardsTheEndpoint() throws Exception {

            String workerSession = sessionTokenFrom(login("asha", "pw-asha"));

            // No session at all.
            mvc.perform(post("/api/admin/workers/asha/reset-device"))
                    .andExpect(status().isForbidden());

            // A perfectly valid session — belonging to a worker.
            mvc.perform(post("/api/admin/workers/asha/reset-device")
                            .cookie(new Cookie(SESSION_COOKIE, workerSession)))
                    .andExpect(status().isForbidden());

            assertThat(users.findByUsername("asha").orElseThrow().hasRegisteredDevice())
                    .as("the binding must survive an unauthorised reset attempt")
                    .isTrue();
        }

        @Test
        @DisplayName("a worker cannot list the workforce either")
        void guardsTheListing() throws Exception {

            String workerSession = sessionTokenFrom(login("asha", "pw-asha"));

            mvc.perform(get("/api/admin/workers").cookie(new Cookie(SESSION_COOKIE, workerSession)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("resetting clears the binding and lets the next login register a new browser")
        void resetAllowsReRegistration() throws Exception {

            login("asha", "pw-asha");                 // bound to browser #1
            String adminSession = sessionTokenFrom(login("root", "pw-root"));

            mvc.perform(post("/api/admin/workers/asha/reset-device")
                            .cookie(new Cookie(SESSION_COOKIE, adminSession)))
                    .andExpect(status().isOk());

            assertThat(users.findByUsername("asha").orElseThrow().hasRegisteredDevice()).isFalse();

            // Browser #2: no device cookie, and now that is allowed again.
            MockHttpServletResponse fresh = login("asha", "pw-asha");
            assertThat(deviceTokenFrom(fresh))
                    .as("the next login registers whichever browser she uses")
                    .isNotNull();
        }

        @Test
        @DisplayName("after a reset, the OLD browser's stale cookie re-registers rather than locking her out")
        void staleCookieRebindsAfterAReset() throws Exception {

            String oldToken = deviceTokenFrom(login("asha", "pw-asha"));
            String adminSession = sessionTokenFrom(login("root", "pw-root"));

            mvc.perform(post("/api/admin/workers/asha/reset-device")
                    .cookie(new Cookie(SESSION_COOKIE, adminSession)));

            // She is sitting at the same PC. The browser still holds the token
            // the reset just orphaned — the browser was never told about it.
            // The rule is "unbound worker, so bind whatever turns up", which
            // gets her straight back in on a brand-new token.
            MockHttpServletResponse response = mvc.perform(post("/api/login")
                            .cookie(new Cookie(DEVICE_COOKIE, oldToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"asha\",\"password\":\"pw-asha\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse();

            assertThat(deviceTokenFrom(response))
                    .as("a fresh token, not the orphaned one")
                    .isNotNull()
                    .isNotEqualTo(oldToken);
        }

        @Test
        @DisplayName("resetting revokes the worker's live sessions")
        void resetSignsTheWorkerOut() throws Exception {

            String ashasSession = sessionTokenFrom(login("asha", "pw-asha"));
            String adminSession = sessionTokenFrom(login("root", "pw-root"));

            mvc.perform(post("/api/admin/workers/asha/reset-device")
                    .cookie(new Cookie(SESSION_COOKIE, adminSession)));

            // Otherwise the machine we just unbound would stay signed in, which
            // is the state the reset existed to end.
            mvc.perform(get("/api/me").cookie(new Cookie(SESSION_COOKIE, ashasSession)))
                    .andExpect(jsonPath("$.found").value(false));

            // The admin's own session is untouched.
            mvc.perform(get("/api/me").cookie(new Cookie(SESSION_COOKIE, adminSession)))
                    .andExpect(jsonPath("$.found").value(true));
        }

        @Test
        @DisplayName("resetting an unbound worker succeeds and changes nothing")
        void resetIsIdempotent() throws Exception {

            String adminSession = sessionTokenFrom(login("root", "pw-root"));

            mvc.perform(post("/api/admin/workers/bilal/reset-device")
                            .cookie(new Cookie(SESSION_COOKIE, adminSession)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("resetting a name that does not exist is a 404, not a silent success")
        void resetUnknownUser() throws Exception {

            String adminSession = sessionTokenFrom(login("root", "pw-root"));

            mvc.perform(post("/api/admin/workers/nobody/reset-device")
                            .cookie(new Cookie(SESSION_COOKIE, adminSession)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("the worker listing shows who is bound, and never the token hash")
        void listingShowsBindingsButNoSecrets() throws Exception {

            String ashasDesk = deviceTokenFrom(login("asha", "pw-asha"));   // bound
            String adminSession = sessionTokenFrom(login("root", "pw-root"));

            String body = mvc.perform(get("/api/admin/workers")
                            .cookie(new Cookie(SESSION_COOKIE, adminSession)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workers[0].username").value("asha"))
                    .andExpect(jsonPath("$.workers[0].deviceRegistered").value(true))
                    .andExpect(jsonPath("$.workers[1].username").value("bilal"))
                    .andExpect(jsonPath("$.workers[1].deviceRegistered").value(false))
                    .andReturn().getResponse().getContentAsString();

            // Admins are not workers and are not in the list.
            assertThat(body).doesNotContain("root");

            // Nothing that could be replayed as a credential, and no password.
            assertThat(body)
                    .doesNotContain("deviceTokenHash")
                    .doesNotContain(ashasDesk)                            // the raw token
                    .doesNotContain(SessionService.hash(ashasDesk))       // nor its fingerprint
                    .doesNotContain("pw-asha");
        }
    }

    // ======================================================================
    //  helpers
    // ======================================================================

    private MockHttpServletResponse login(String username, String password) throws Exception {
        return mvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private static String sessionTokenFrom(MockHttpServletResponse response) {
        return valueOf(headerFor(response, SESSION_COOKIE));
    }

    private static String deviceTokenFrom(MockHttpServletResponse response) {
        return valueOf(deviceCookieHeaderFrom(response));
    }

    /**
     * A login can emit two Set-Cookie headers, so picking one by index would be
     * a test that breaks the day the order changes. This finds it by name.
     */
    private static String deviceCookieHeaderFrom(MockHttpServletResponse response) {
        return headerFor(response, DEVICE_COOKIE);
    }

    private static String headerFor(MockHttpServletResponse response, String cookieName) {
        List<String> headers = response.getHeaders(HttpHeaders.SET_COOKIE);
        return headers.stream()
                .filter(header -> header.startsWith(cookieName + "="))
                .findFirst()
                .orElse(null);
    }

    /** "did=8f14e45f-...; Path=/; Max-Age=...; Secure; HttpOnly; SameSite=Lax" -> the value */
    private static String valueOf(String setCookieHeader) {
        if (setCookieHeader == null) {
            return null;
        }
        String firstPair = setCookieHeader.split(";", 2)[0];
        String value = firstPair.substring(firstPair.indexOf('=') + 1);
        return value.isEmpty() ? null : value;
    }
}
