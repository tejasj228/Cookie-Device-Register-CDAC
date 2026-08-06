/**
 * Every call the frontend makes to the Java backend lives here.
 *
 * Note the `credentials: "include"` on each request — that is what tells the
 * browser "attach my cookies to this request, and save any cookie that comes
 * back". Without it the whole demo would not work.
 *
 * We call "/api/..." (not https://localhost:8443) because src/setupProxy.js
 * forwards those calls to Spring Boot during development. That indirection is
 * not just tidiness: it keeps the page and the API on ONE origin, and a
 * same-origin setup is what lets a SameSite cookie work with no CORS
 * configuration at all.
 *
 * ---------------------------------------------------------------------------
 *  What this file deliberately cannot do any more
 * ---------------------------------------------------------------------------
 *  There is no way, anywhere in this codebase, for JavaScript to read the
 *  session id. `document.cookie` does not list it, because the cookie is
 *  HttpOnly. That is not an oversight to work around — it is the protection:
 *  if our code cannot read it, neither can a script that gets injected into
 *  our page.
 *
 *  So the frontend never handles the session id at all. The browser attaches
 *  it, Java resolves it, and the answer comes back as ordinary JSON.
 * ---------------------------------------------------------------------------
 */

/**
 * Where the API lives.
 *
 * Empty by default, which makes every call below a same-origin relative URL —
 * "/api/me" against whatever host is serving the page. That is the arrangement
 * the whole design wants, and all three environments produce it:
 *
 *   development   src/setupProxy.js forwards /api to Spring Boot on 8443
 *   production    the rewrite in vercel.json forwards /api to the backend host
 *   fallback      set REACT_APP_API_BASE to the backend's own URL
 *
 * Only reach for the fallback if the rewrite is not an option, and know what it
 * costs: the page and the API become different sites, so the cookies have to be
 * weakened to SameSite=None to work at all, and the backend needs CORS opened
 * to this origin. Same-origin is worth some effort to keep.
 */
const API_BASE = (process.env.REACT_APP_API_BASE || "").replace(/\/$/, "");

const url = (path) => `${API_BASE}${path}`;

/**
 * Turns a response into data, or throws.
 *
 * The thrown Error carries the server's `code` alongside its message. The
 * message is for the person reading the screen and will change the first time
 * somebody rewords it or translates it; the code is for our code, so that
 * telling "wrong password" apart from "wrong workstation" never becomes a
 * string comparison against English prose.
 */
async function handle(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.message || "Something went wrong.");
    error.code = data.code || null;
    error.status = response.status;
    throw error;
  }
  return data;
}

const jsonPost = (path, body) =>
  fetch(url(path), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify(body || {}),
  }).then(handle);

/**
 * Asks the server "does the session id in my cookie point at a live session?".
 * If it does, we are signed in and the profile comes back with it — read out of
 * the database, not out of the cookie. This is called on every page load, and
 * it is what keeps you signed in across a refresh.
 */
export const fetchMe = () =>
  fetch(url("/api/me"), { credentials: "include" }).then(handle);

/** Creates a new row in the database. */
export const register = (payload) => jsonPost("/api/register", payload);

/** Checks the database, starts a session, and sets the hardened cookie. */
export const login = (payload) => jsonPost("/api/login", payload);

/**
 * Signs out for real: the session row is deleted on the SERVER, so even a copy
 * of the cookie taken earlier stops working immediately. Clearing the cookie in
 * the browser is only the tidy-up afterwards.
 */
export const logout = () => jsonPost("/api/logout");

/**
 * ---------------------------------------------------------------------------
 *  Admin calls
 * ---------------------------------------------------------------------------
 *  Both of these are 403 for anybody who is not an admin, and the check happens
 *  on the server every time. Nothing here is a security boundary — the admin
 *  panel is hidden from workers only so the screen makes sense to them, and a
 *  worker who unhides it in DevTools gets a row of buttons that all fail.
 * ---------------------------------------------------------------------------
 */

/** Every worker, and whether each one is currently bound to a browser. */
export const fetchWorkers = () =>
  fetch(url("/api/admin/workers"), { credentials: "include" }).then(handle);

/**
 * Unbinds a worker. Their next successful sign-in registers whichever browser
 * they are sitting at, and their current sessions are ended.
 */
export const resetWorkerDevice = (username) =>
  jsonPost(`/api/admin/workers/${encodeURIComponent(username)}/reset-device`);
