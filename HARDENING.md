# Hardening the browser side

Two questions this file answers:

1. Can a user be stopped from deleting the device cookie?
2. If they delete it anyway, is anyone stuck?

Short answers: **no, not from the application** — but a managed Windows fleet can
raise the cost a long way. And **no, nobody is stuck** — the admin reset already
handles it, and does not need the browser's cooperation.

---

## 1. Why the app cannot protect the cookie

There is no web platform mechanism that makes a cookie undeletable, and there
should not be. The cookie jar is storage on the user's machine, and browsers
treat "the user can clear their own data" as non-negotiable — every proposal to
give sites a permanent, unclearable client-side marker has been rejected for the
obvious reason that a supercookie is a tracking primitive.

`HttpOnly` is worth understanding precisely here, because it is easy to
over-read. It stops **JavaScript** from touching the cookie: `document.cookie`
cannot see it, so an XSS bug cannot exfiltrate it. It does nothing at all about
a human in DevTools → Application → Cookies, which is the browser's own UI
operating outside the page's JavaScript context entirely. Same for the settings
screen, `Ctrl+Shift+Del`, or a different browser profile.

So the honest framing, which is the same one in `DeviceBindingService`: this is a
control against **casual** account sharing in a trusted office. A user who
deliberately opens DevTools to defeat it is outside the threat model, and the
answer to that is HR, not a cookie flag.

---

## 2. What actually raises the cost: enterprise browser policy

On domain-joined or Intune-managed Windows machines, Chrome and Edge read
policies that the user cannot override. This is the real lever.

Set via Group Policy (import the Chrome ADMX templates into
`Computer Configuration → Policies → Administrative Templates → Google Chrome`),
or directly in the registry under:

```
Chrome   HKLM\SOFTWARE\Policies\Google\Chrome
Edge     HKLM\SOFTWARE\Policies\Microsoft\Edge
```

`HKLM` rather than `HKCU` matters — a standard user cannot write to it.

| Policy | Value | Effect |
|---|---|---|
| `DeveloperToolsAvailability` | `2` (DWORD) | **The important one.** DevTools completely unavailable — no F12, no context-menu Inspect, no JavaScript console, no keyboard shortcut. This closes the exact door in your question. |
| `IncognitoModeAvailability` | `1` (DWORD) | Disables incognito. Without this a worker can register a binding in a private window that evaporates on close, and lock themselves out by accident. |
| `URLBlocklist` | see below | Blocks the settings pages that clear cookies. |
| `BrowserGuestModeEnabled` | `0` (DWORD) | Stops guest profiles, which get a fresh cookie jar. |

`URLBlocklist` is a list of strings (`REG_SZ` values named `1`, `2`, `3`… under
a `URLBlocklist` subkey):

```
chrome://settings/clearBrowserData
chrome://settings/siteData
chrome://settings/content/all
chrome://settings/cookies
```

### The policy that would silently destroy this feature

**`ClearBrowsingDataOnExitList` must not include cookies.** If it does, every
worker's device binding is wiped when they close the browser, and every single
one of them is locked out the next morning needing an admin reset.

Check for it before you deploy. It is a common item on corporate browser
baselines, and the failure looks like the feature is broken rather than like a
policy conflict — which is a bad hour to spend. Same applies to
`CookiesSessionOnlyForUrls` and to `DefaultCookiesSetting = 4` (session-only),
either of which produces the identical symptom.

If your baseline clears cookies on exit as a general rule, add an exception for
this app's origin via `CookiesAllowedForUrls` rather than turning the baseline
off wholesale.

### What this does not stop

Be clear-eyed about the ceiling:

- A user with **local administrator rights** can edit `HKLM` and undo all of it.
- A **portable browser** on a USB stick reads none of these policies. Blocking
  that is AppLocker or WDAC territory — a fleet management project, not a
  browser setting.
- A user's **own laptop or phone** is not managed at all.

Each of those is a much higher bar than pressing F12, which is the point. You are
not building a wall, you are making the casual path stop working.

---

## 3. The lockout scenario, traced

> A user deletes the `did` cookie in DevTools. They cannot log in — the browser
> has no device id. They cannot use another machine — the database still has
> their binding. And the admin cannot clear it, because the deletion happened in
> the browser and never reached the database.

The first two steps are exactly right. The third is the one to correct, and the
correction is the whole answer: **the admin reset is a pure database operation.
It never reads or needs the browser's cookie.**

`resetDevice()` calls `worker.clearDevice()` on the row and saves it. There is no
part of it that consults, requires, or is blocked by what any browser is holding.
The cookie being gone from the browser is not an obstacle to the reset — it is
*the state the reset exists to recover from*.

Walking it through:

| Step | Browser | Database | Result |
|---|---|---|---|
| 1. Registered normally | `did=T` | `hash(T)` | works |
| 2. User deletes the cookie | *(nothing)* | `hash(T)` | **denied** — "registered to a different workstation" |
| 3. Admin clicks Reset | *(nothing)* | `null` | binding cleared, sessions revoked |
| 4. Worker logs in again | *(nothing)* | `null` | **registers a fresh token** — back in |

Step 4 is the row `no cookie, no binding → REGISTER` in the decision table. The
worker is indistinguishable from someone signing in for the first time, which is
precisely correct: they are, on this browser, as far as anyone can tell.

This is covered by `resetAllowsReRegistration` in `DeviceBindingTest`, which
resets a bound worker and asserts the next cookie-less login mints a new token.

### The admin can do this from anywhere, including that same PC

Admins are exempt from device binding entirely — `evaluate()` returns
`allowed()` for them before reading a single thing. So an administrator can walk
to the locked-out worker's desk, sign in on that very browser, reset the worker,
and sign out, without disturbing anything. That exemption is not a convenience;
it is what makes the escape hatch reachable, because the machine that needs
fixing is by definition a machine somebody else is bound to.

### The two-reset case

The one situation needing more than a single click: a worker sits at a browser
already bound to a *different* worker, and is themselves bound elsewhere. Both
gates are shut, so both need opening — reset the worker who owns the browser,
then reset the one trying to sign in. After that the next login binds cleanly.
Still no deadlock, just two clicks.

### Why a stale cookie never wedges anything

If an admin resets worker X, the browser still holds X's old cookie — nobody told
it. That token now matches no row in the database. Rather than treating an
unrecognised cookie as a hard stop, `evaluate()` branches on the **worker's**
state: an unbound worker binds whatever turns up, so the orphaned cookie is
simply overwritten with a fresh one. Tested by `staleCookieRebindsAfterAReset`.

An unrecognised cookie can therefore never permanently poison a workstation.

---

## 4. Operational advice

Given cookie deletion means an admin reset, two things are worth doing:

- **Keep more than one admin account.** The reset is the only escape hatch, and
  a single admin who is on leave is a single point of failure for the whole
  office.
- **Tell workers not to run "clear browsing data".** The lockout is recoverable
  in about ten seconds, but only if somebody knows to ask. The rejection message
  already says to contact an administrator, which is most of the battle.

If resets become frequent enough to be annoying, that is a signal the binding is
too strict for how the office actually works — not a signal to weaken the check
into something that silently rebinds, which would leave you with the maintenance
cost of the feature and none of its value.
