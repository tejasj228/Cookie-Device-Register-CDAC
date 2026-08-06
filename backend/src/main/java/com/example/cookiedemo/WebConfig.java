package com.example.cookiedemo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ==========================================================================
 *  CORS — off by default, and that is the good outcome
 * ==========================================================================
 *  The whole design of this app assumes the page and the API sit on ONE
 *  origin. In development src/setupProxy.js arranges that; in production the
 *  Vercel rewrite in frontend/vercel.json does the same job, forwarding
 *  /api/* to the backend so the browser only ever sees one hostname.
 *
 *  Same-origin is worth arranging rather than working around, because it is
 *  what lets the cookies stay SameSite=Lax. A Lax cookie is not attached to
 *  requests other sites make on your behalf, which is CSRF protection you get
 *  for free and do not have to maintain. Go cross-origin and you must weaken
 *  both cookies to SameSite=None to make them work at all — and None means
 *  "attach this to any site's request to us", which hands back the very
 *  protection Lax was giving you.
 *
 *  So this class exists as a documented escape hatch, not a default. It does
 *  nothing at all unless app.cors.allowed-origins is set. Set it only if you
 *  are deliberately serving the frontend from a different hostname than the
 *  API, and if you do, you must also set:
 *
 *      app.cookie.same-site=None
 *      app.cookie.secure=true        (browsers reject None without Secure)
 *
 *  Note allowedOrigins takes an explicit list and never "*". A wildcard is
 *  illegal alongside allowCredentials(true) — the spec forbids the
 *  combination, and browsers enforce it — because "any site may call us, with
 *  the user's cookies attached" is a description of the attack rather than of
 *  a configuration.
 * ==========================================================================
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Comma-separated. Empty (the default) means CORS is not configured at all. */
    private final String allowedOrigins;

    public WebConfig(@Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins == null ? "" : allowedOrigins.trim();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {

        if (allowedOrigins.isEmpty()) {
            return;
        }

        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split("\\s*,\\s*"))
                .allowedMethods("GET", "POST")
                .allowCredentials(true)   // without this the browser sends no cookies
                .maxAge(3600);
    }
}
