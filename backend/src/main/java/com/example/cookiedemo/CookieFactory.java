package com.example.cookiedemo;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ==========================================================================
 *  ONE PLACE THAT KNOWS HOW TO WRITE A COOKIE
 * ==========================================================================
 *  This app now sets two cookies, and they have very different jobs:
 *
 *    sid  the session. Short-lived by comparison, rotated on every login,
 *         deleted on sign-out. Losing it means "sign in again".
 *
 *    did  the device registration. Persistent, never rotated, and NOT cleared
 *         on sign-out — the whole point is that it outlives the session and
 *         keeps identifying this browser as one particular worker's desk.
 *
 *  What they share is the hardening: HttpOnly, Secure, SameSite, Path=/, and
 *  no Domain. That list is easy to get subtly wrong twice, and a device cookie
 *  that quietly lacked HttpOnly would be the more dangerous of the two, since
 *  it is the one that lives for years. So the attributes are set here, once,
 *  and each caller supplies only the two things that genuinely differ: the
 *  name and the lifetime.
 * ==========================================================================
 */
@Component
public class CookieFactory {

    /** Secure attribute -> "browser, only ever send this back over HTTPS". */
    private final boolean secure;

    /** Lax / Strict / None. Lax is right whenever the API is same-origin with the page. */
    private final String sameSite;

    /*
     * The nested defaults keep the older, session-specific property names
     * working: set app.cookie.* to govern both cookies at once, or leave it
     * unset and app.session.cookie-* still applies, exactly as before.
     */
    public CookieFactory(@Value("${app.cookie.secure:${app.session.cookie-secure:true}}") boolean secure,
                         @Value("${app.cookie.same-site:${app.session.cookie-same-site:Lax}}") String sameSite) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    /**
     * Builds a Set-Cookie header value.
     *
     * The finished header looks like this:
     *
     *   Set-Cookie: did=8f14e45f-...; Path=/; Max-Age=315360000; Expires=...;
     *               Secure; HttpOnly; SameSite=Lax
     *
     * Attribute by attribute:
     *
     *   HttpOnly  JavaScript cannot see this cookie. document.cookie simply
     *             does not list it. If a bug ever lets an attacker run script
     *             on our page, they can neither read the session id nor read —
     *             or overwrite — the device token.
     *
     *   Secure    The browser will only attach it to https:// requests.
     *
     *   SameSite  Lax = send it on normal navigation to our site, but NOT on
     *             requests another website fires at us in the background,
     *             which is what stops CSRF. Set it to None only if the API is
     *             genuinely on a different site from the page, and note that
     *             browsers reject None unless Secure is also on.
     *
     *   Path=/    Sent for every page of our site.
     *
     *   (no Domain) Host-only: this exact host, no subdomain of it.
     */
    public ResponseCookie build(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    /** The header that tells a browser to throw a cookie away. */
    public ResponseCookie expire(String name) {
        return build(name, "", Duration.ZERO);
    }

    /** Pulls one cookie's raw value out of an incoming request, or null. */
    public static String read(HttpServletRequest request, String name) {

        if (request.getCookies() == null) {
            return null;
        }

        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
