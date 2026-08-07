# Deploying: Vercel + Koyeb + Neon

The app is one thing to a browser and three things to you.

| Piece | Where | Why there |
|---|---|---|
| React build | **Vercel** | Static files on a CDN, which is what Vercel is best at |
| Spring Boot API | **Koyeb** (Docker) | Vercel cannot run a JVM — see below |
| Postgres | **Neon** | Managed, free tier, created from Vercel's own dashboard |

---

## Why the backend is not on Vercel

Vercel runs short-lived serverless functions in JavaScript, Python, Go and Ruby.
It has no Java runtime and, more fundamentally, no product that runs a long-lived
process or a container. So there is no way to deploy Spring Boot there.

It is not only the missing runtime. This app takes **~3 seconds** to start a
Spring context, which a platform that scales to zero would pay on every cold
request; the hourly `@Scheduled` session purge needs a live process and would
simply never fire; and Hikari's connection pool assumes one long-lived server
rather than N concurrent function instances.

So the API goes on a container host, and **nothing here is Koyeb-specific** — the
same `backend/Dockerfile` runs unchanged anywhere that takes a container.

### Why Koyeb rather than Render

Koyeb's free instance is **always on**. Render's free tier sleeps after 15 minutes
idle and takes ~50 seconds to wake, which makes the first login after any quiet
spell look broken. For something you will demo, that difference matters more than
anything else on the list.

| | Free instance | Sleeps? | Card needed? |
|---|---|---|---|
| **Koyeb** | 0.1 vCPU, 512 MB | **no** | usually not |
| Render | 0.1 vCPU, 512 MB | yes, ~50s wake | no |
| Fly.io | free allowance retired | n/a | yes, $5/mo minimum |
| Railway | ~500 compute-hours trial | no | yes, after the trial |

512 MB is small, and `backend/Dockerfile` is tuned for it — the JVM flags cap the
heap at 60% rather than the usual 75%, because heap is not the only thing in the
container and 75% would get it OOM-killed under load.

### The rewrite is the important part

`frontend/vercel.json` forwards `/api/*` from the Vercel domain to Koyeb:

```
browser ──► your-app.vercel.app/api/login ──► your-api.koyeb.app/api/login
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

## Order of operations

Creating the database from Vercel means the Vercel project has to exist first —
it is what the integration attaches the database to. So the sequence is:

```
1. Vercel project   (so there is something to attach a database to)
2. Neon database    (created from inside Vercel)
3. Koyeb API        (needs the database credentials from step 2)
4. Rewrite + push   (needs the Koyeb hostname from step 3)
```

The site is live but non-functional between steps 1 and 4. That is expected —
the login form is there, but `/api/login` has nowhere to go until step 4.

---

## 1. Vercel — the frontend project

Import the repo at [vercel.com](https://vercel.com) with:

- **Root Directory:** `frontend`
- Framework, build command and output directory come from `vercel.json`.

Add **no** environment variables. Leaving `REACT_APP_API_BASE` unset is what
makes the frontend call relative `/api/...` URLs so the rewrite can do its job.

The build will succeed and the site will load. Signing in will not work yet.

---

## 2. Neon — the database

Create the database through the **Neon integration in the Vercel dashboard**:
**Storage → Create Database → Neon**, or the
[Neon listing on the Vercel Marketplace](https://vercel.com/marketplace/neon).
Choosing the *Vercel-managed* option creates the Neon account and project for
you and keeps billing inside Vercel, so there is nothing to set up on neon.com
first.

When it finishes, the integration writes a set of variables into your **Vercel
project's** environment:

```
DATABASE_URL             pooled connection   (through PgBouncer)
DATABASE_URL_UNPOOLED    direct connection
POSTGRES_URL, PGHOST, PGUSER, PGPASSWORD, PGDATABASE   (legacy aliases)
```

### The catch: those variables are in the wrong place

The integration wires the database into **Vercel**, and our JVM does not run on
Vercel — it runs on Koyeb. Vercel environment variables are visible only to
Vercel builds and functions, so the backend will never see them. You have to
read the values out of Vercel and paste them into Koyeb by hand, once.

Open **Vercel → your project → Settings → Environment Variables**, reveal
`DATABASE_URL_UNPOOLED`, and take it apart.

*(This is harmless for the frontend, incidentally: Create React App only inlines
variables prefixed `REACT_APP_`, so a database password sitting in the Vercel
build environment cannot end up in your JavaScript bundle.)*

### Use the UNPOOLED one

Not `DATABASE_URL`, despite it being the one Vercel presents first. The pooled
endpoint is PgBouncer in **transaction** mode, and the Postgres JDBC driver
promotes queries to server-side prepared statements after a few executions. Those
two disagree: the prepared statement is created on one pooled backend and the
next execution is routed to a different one, producing sporadic
`prepared statement "S_1" already exists` errors under load — the worst kind of
bug, because it needs traffic to show up and looks like nothing at all in
testing.

You would want the pooler if you were running many short-lived instances. You are
running **one** always-on container with a Hikari pool of 5
(`DB_POOL_SIZE`), which is nothing against Neon's connection limit. Direct is
both simpler and safer here.

If you ever scale to several instances, switch to `DATABASE_URL` and add
`&prepareThreshold=0` to disable driver-side prepared statements.

### Converting to JDBC

Neon hands you a URL with the credentials embedded. **JDBC needs a different
shape**, split into three values:

```
DATABASE_URL_UNPOOLED gives you:
  postgresql://neondb_owner:npg_XXXX@ep-cool-name-12345.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require

You need:
  SPRING_DATASOURCE_URL       jdbc:postgresql://ep-cool-name-12345.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
  SPRING_DATASOURCE_USERNAME  neondb_owner
  SPRING_DATASOURCE_PASSWORD  npg_XXXX
```

Four things to get right:

- the **`jdbc:`** prefix on the front (and `postgresql://`, not `postgres://`)
- the **credentials pulled out** of the URL into their own variables
- **`sslmode=require` kept** — this connection crosses the public internet
- **everything else dropped.** Strip `channel_binding`, `options` and any other
  parameter Neon appended. They are for `libpq`-based clients; the JDBC driver
  does not need them and can object to them.

### The schema

Nothing to create. `spring.jpa.hibernate.ddl-auto=update` builds `APP_USER` and
`USER_SESSION` on first boot. (For anything real, replace that with Flyway, so
schema changes are reviewed files rather than something Hibernate infers at
startup and applies to a live database on its own.)

---

## 3. Koyeb — the API

Sign in at [koyeb.com](https://koyeb.com) with GitHub, then **Create Web Service**:

| Field | Value |
|---|---|
| Source | **GitHub** → `Cookie-Device-Register-CDAC` |
| Branch | `main` |
| Builder | **Dockerfile** |
| Dockerfile location | `backend/Dockerfile` |
| Work directory | `backend` |
| Instance | **Free** |
| Port | `8000` |
| Health check path | `/api/me` |

Two of those need a word of explanation.

**Work directory `backend`.** This tells Koyeb to treat `backend/` as the Docker
build context. The Dockerfile copies `pom.xml` and `src` as if it were sitting in
that folder, so pointing the context at the repo root would fail to find them.

**Port.** Koyeb's default service port is 8000, and the app reads `${PORT:8443}`
— so either set Koyeb's port to 8000 and add a `PORT=8000` variable, or set both
to whatever you prefer. They just have to agree. The health check on `/api/me` is
a good liveness signal because it answers `{"found":false}` with no cookie, which
proves the database is reachable too.

Then set the environment variables:

| Variable | Value | Why |
|---|---|---|
| `PORT` | `8000` | must match the service port above |
| `SPRING_DATASOURCE_URL` | the `jdbc:` URL from step 2 | |
| `SPRING_DATASOURCE_USERNAME` | Neon user | |
| `SPRING_DATASOURCE_PASSWORD` | Neon password | mark it **Secret** |
| `APP_BOOTSTRAP_ADMIN_USERNAME` | your admin login | **set this** |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | a real password | **set this**, mark it Secret — the default is `admin`/`admin` |
| `SERVER_SSL_ENABLED` | `false` | Koyeb terminates HTTPS at its edge |
| `APP_COOKIE_SECURE` | `true` | the *browser's* connection is still HTTPS |
| `APP_COOKIE_SAME_SITE` | `Lax` | correct as long as you use the rewrite |
| `H2_CONSOLE_ENABLED` | `false` | |

The last four are already defaults in the Dockerfile, so you can skip them; they
are listed so you know what is in force and where to change it.

**`SERVER_SSL_ENABLED=false` is not a downgrade.** Koyeb's edge serves real,
CA-signed HTTPS to the browser and speaks plain HTTP to your container over its
internal network. That is how essentially every deployed app works, and it is why
the container ships no certificate. The cookies stay `Secure` because what
`Secure` cares about is the browser's connection, which is encrypted.

The first build takes a few minutes — it is compiling a Spring Boot app from
scratch. When it goes green, note your URL: something like
`your-service-yourorg.koyeb.app`. Check it works:

```bash
curl https://your-service-yourorg.koyeb.app/api/me
```

You want `{"found":false}`. That is the whole stack answering: Koyeb routed it,
Spring handled it, and Neon was reachable.

---

## 4. Point the frontend at it

Edit `frontend/vercel.json` and replace the placeholder with your Koyeb hostname:

```json
{
  "source": "/api/:path*",
  "destination": "https://your-service-yourorg.koyeb.app/api/:path*"
}
```

It has to be a literal — Vercel does not expand environment variables inside
rewrite destinations. Commit and push:

```bash
git add frontend/vercel.json && git commit -m "Point the API rewrite at Koyeb" && git push
```

Vercel redeploys on the push. Now the site works end to end.

---

## 5. Verify

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

If step 3 shows no cookies at all, the rewrite is not reaching Koyeb — check the
hostname in `vercel.json` and look at the Network tab for the status of
`/api/login`.

---

## Using a different host instead

The Dockerfile is the whole deployment, so every container host is the same four
steps: point it at the repo, tell it `backend/Dockerfile` with `backend` as the
context, set the environment variables from step 2, and put the resulting
hostname into `vercel.json`. Only the dashboard differs.

- **Railway** — the smoothest UI of the lot. Free trial credit, then $5/month.
- **Fly.io** — excellent, Docker-native, no free allowance for new accounts
  ($5/month minimum). Needs a `fly.toml`; `fly launch --dockerfile backend/Dockerfile`
  generates one.
- **Google Cloud Run** — a genuinely generous always-free tier and very fast cold
  starts, but it scales to zero and the GCP console is a lot of platform to meet
  for one container.
- **Render** — works fine, but the free tier sleeps.

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
| Vercel | `REACT_APP_API_BASE` | `https://your-service-yourorg.koyeb.app` |
| Koyeb | `APP_CORS_ALLOWED_ORIGINS` | `https://your-app.vercel.app` |
| Koyeb | `APP_COOKIE_SAME_SITE` | `None` |
| Koyeb | `APP_COOKIE_SECURE` | `true` (browsers reject `None` without it) |

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
