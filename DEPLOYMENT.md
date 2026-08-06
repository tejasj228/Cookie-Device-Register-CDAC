# Deploying: Vercel + Render + Neon

The app is one thing to a browser and three things to you.

| Piece | Where | Why there |
|---|---|---|
| React build | **Vercel** | Static files on a CDN, which is what Vercel is best at |
| Spring Boot API | **Render** (Docker) | Vercel cannot run a JVM — see below |
| Postgres | **Neon** | Managed, free tier, no server to look after |

---

## Why the backend is not on Vercel

Vercel runs short-lived serverless functions in JavaScript, Python, Go and Ruby.
It does not run a long-lived JVM process, so there is no way to deploy a Spring
Boot application there. This is not a limitation to work around — it is a
different shape of platform.

So the API goes on a container host. Render is used below because its free tier
needs no card, but **nothing here is Render-specific**: the same `backend/Dockerfile`
runs unchanged on Railway, Fly.io, Koyeb, or any box with Docker on it.

### The rewrite is the important part

`frontend/vercel.json` forwards `/api/*` from the Vercel domain to Render:

```
browser ──► your-app.vercel.app/api/login ──► cookie-backend.onrender.com/api/login
            (the only host the browser ever sees)
```

The browser never learns the backend exists. Page and API are the **same origin**
as far as it is concerned, which buys three things at once:

- **No CORS.** Nothing to configure, nothing to get subtly wrong.
- **Cookies stay first-party.** They are set on the Vercel domain, so browsers
  with third-party-cookie blocking on (which is most of them now, by default)
  do not silently drop your login.
- **`SameSite=Lax` keeps working.** Lax is CSRF protection you get for free.
  Going cross-origin would force both cookies to `SameSite=None` — "attach this
  to any site's request to us" — handing back exactly the protection Lax gives.

That is worth the one line of config. There is a fallback if you cannot use it,
at the bottom of this file, and it is strictly worse.

---

## 1. Neon — the database

1. Create a project at [neon.tech](https://neon.tech). Any region; pick one near
   your Render region to save a few milliseconds per query.
2. Open **Connection Details** and copy the connection string. Neon shows you a
   `postgresql://user:pass@host/db` URL — **JDBC needs a different shape**, so
   convert it:

   ```
   Neon gives you:
     postgresql://neondb_owner:npg_XXXX@ep-cool-name-12345.ap-southeast-1.aws.neon.tech/neondb?sslmode=require

   You need three separate values:
     SPRING_DATASOURCE_URL       jdbc:postgresql://ep-cool-name-12345.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
     SPRING_DATASOURCE_USERNAME  neondb_owner
     SPRING_DATASOURCE_PASSWORD  npg_XXXX
   ```

   Three things to get right: the `jdbc:` prefix, the credentials pulled **out**
   of the URL into their own variables, and `sslmode=require` kept — the
   connection crosses the public internet.

No schema to create. `spring.jpa.hibernate.ddl-auto=update` builds `APP_USER` and
`USER_SESSION` on first boot. (For anything real you would replace that with
Flyway, so schema changes are reviewed files rather than something Hibernate
infers at startup and applies to a live database on its own.)

---

## 2. Render — the API

Push the repo to GitHub, then either point Render at `render.yaml` (**New →
Blueprint**) or create a web service by hand with:

- **Runtime:** Docker
- **Dockerfile path:** `backend/Dockerfile`
- **Docker context:** `backend`
- **Health check path:** `/api/me`

Then set the environment variables:

| Variable | Value | Why |
|---|---|---|
| `SPRING_DATASOURCE_URL` | the `jdbc:` URL from step 1 | |
| `SPRING_DATASOURCE_USERNAME` | Neon user | |
| `SPRING_DATASOURCE_PASSWORD` | Neon password | |
| `APP_BOOTSTRAP_ADMIN_USERNAME` | your admin login | **set this** |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | a real password | **set this** — the default is `admin`/`admin` |
| `SERVER_SSL_ENABLED` | `false` | Render terminates HTTPS at its edge |
| `APP_COOKIE_SECURE` | `true` | the *browser's* connection is still HTTPS |
| `APP_COOKIE_SAME_SITE` | `Lax` | correct as long as you use the rewrite |
| `H2_CONSOLE_ENABLED` | `false` | |

**`SERVER_SSL_ENABLED=false` is not a downgrade.** Render's load balancer serves
real, CA-signed HTTPS to the browser and speaks plain HTTP to your container over
its own internal network. That is how essentially every deployed app works, and
it is why the container ships no certificate. The cookies stay `Secure` because
what `Secure` cares about is the browser's connection, which is encrypted.

Note the port: the app reads `${PORT:8443}`, and Render injects `PORT`. Nothing to
set.

**Free tier caveat:** instances sleep after 15 minutes idle and take ~50 seconds
to wake. The first login after a quiet spell will look broken and is not. Upgrade
the instance, or warm it with a cron ping, if that matters.

---

## 3. Vercel — the frontend

**Before deploying, edit `frontend/vercel.json`** and put your Render hostname in:

```json
{
  "source": "/api/:path*",
  "destination": "https://cookie-backend-abc123.onrender.com/api/:path*"
}
```

The placeholder is `REPLACE-WITH-YOUR-BACKEND.onrender.com`. It has to be a
literal — Vercel does not expand environment variables inside rewrite
destinations.

Then import the repo at [vercel.com](https://vercel.com) with:

- **Root Directory:** `frontend`
- Framework, build command and output directory are all picked up from
  `vercel.json`.

No environment variables. `REACT_APP_API_BASE` stays unset, which is what makes
the frontend call relative `/api/...` URLs and lets the rewrite do its job.

---

## 4. After the first deploy

1. Open the Vercel URL. You should get the sign-in panel.
2. Sign in as your admin. You should see **Workstations** — an empty list.
3. Create a worker account, sign in, and confirm two cookies in
   **DevTools → Application → Cookies**: `sid` and `did`, both `HttpOnly`, both
   `Secure`, `did` with an expiry years out.
4. Open a **different browser** (not a new tab — a different cookie jar) and try
   the same worker. You should be refused with "already registered to a different
   workstation".
5. Sign in as the admin, reset that worker, and confirm they can now register the
   second browser.

If step 3 shows no cookies at all, the rewrite is not reaching Render — check the
hostname in `vercel.json` and look at the Network tab for the status of
`/api/login`.

---

## Running locally against Neon

Useful for confirming Postgres behaves the same as H2 before you deploy:

```bash
cd backend
SPRING_DATASOURCE_URL="jdbc:postgresql://ep-...neon.tech/neondb?sslmode=require" \
SPRING_DATASOURCE_USERNAME=neondb_owner \
SPRING_DATASOURCE_PASSWORD=npg_XXXX \
./mvnw spring-boot:run
```

Nothing else changes. Both drivers are in the jar and Spring picks one by looking
at the URL, so the same build runs against a file on your laptop and against Neon
in production — one environment variable is the whole difference.

The test suite always uses in-memory H2 and never touches Neon.

---

## Fallback: no rewrite, direct CORS

Only if the rewrite is genuinely unavailable. You lose the CSRF protection that
`SameSite=Lax` was providing, and you take on third-party-cookie blocking as a
live risk.

| Where | Variable | Value |
|---|---|---|
| Vercel | `REACT_APP_API_BASE` | `https://your-backend.onrender.com` |
| Render | `APP_CORS_ALLOWED_ORIGINS` | `https://your-app.vercel.app` |
| Render | `APP_COOKIE_SAME_SITE` | `None` |
| Render | `APP_COOKIE_SECURE` | `true` (browsers reject `None` without it) |

`APP_CORS_ALLOWED_ORIGINS` takes an explicit comma-separated list and never `*` —
a wildcard is illegal alongside credentialed requests, because "any site may call
us with the user's cookies attached" describes an attack rather than a config.

---

## Before this is a real deployment

Two things in this project are demo simplifications, and device binding does not
change either of them:

- **Passwords are stored in plain text.** `AppUser.password` is compared with
  `.equals()`. This was true before this feature and was deliberately left alone,
  but it is the first thing to fix: add `spring-boot-starter-security` and store
  a BCrypt hash.
- **`ddl-auto=update` lets Hibernate alter the live schema at startup.** Fine for
  a demo, not for data anyone cares about. Use Flyway.

Device binding is a control against *casual* account sharing in a trusted office.
It is a cookie, so it does not survive a user who clears their cookies (they need
an admin reset) and does not stop a user who deliberately copies their cookie
store to another machine. That tradeoff is the honest one for the threat it is
aimed at; it is not a substitute for real authentication.
