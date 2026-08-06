/**
 * ===========================================================================
 *  Why this file exists now
 * ===========================================================================
 *  package.json used to have one line:
 *
 *      "proxy": "http://localhost:8080"
 *
 *  which quietly forwarded every /api/... call from the React dev server to
 *  Spring Boot. It cannot do the job any more, for one reason: the backend now
 *  speaks HTTPS with a self-signed certificate, and the simple proxy refuses to
 *  connect to a certificate it cannot verify. (Which is correct of it! That
 *  check is the entire point of certificate validation.)
 *
 *  Create React App looks for src/setupProxy.js and, if it finds one, uses it
 *  INSTEAD of the "proxy" key — so that key has been removed to avoid two
 *  sources of truth.
 *
 *  Note this proxy is a development convenience only. `npm run build` produces
 *  static files that know nothing about it; in production the React bundle and
 *  the API sit behind one web server or CDN, which is what keeps them
 *  same-origin — and same-origin is what lets the cookie work without CORS.
 * ===========================================================================
 */
const { createProxyMiddleware } = require("http-proxy-middleware");

module.exports = function (app) {
  app.use(
    "/api",
    createProxyMiddleware({
      target: "https://localhost:8443",

      // Rewrites the Host header to localhost:8443 so Spring sees a request
      // addressed to itself.
      changeOrigin: true,

      // ---------------------------------------------------------------
      //  secure: false  ==  "do not verify the backend's certificate"
      // ---------------------------------------------------------------
      //  This line is here ONLY because our development certificate is
      //  self-signed and therefore untrusted by design. It is not a shortcut
      //  to copy into anything real: switching it off in production would
      //  mean the proxy happily connects to whoever answers, which is exactly
      //  the man-in-the-middle attack HTTPS exists to prevent.
      //
      //  The traffic is still encrypted either way. What is turned off is the
      //  check on WHO is at the other end.
      // ---------------------------------------------------------------
      secure: false,
    })
  );
};
