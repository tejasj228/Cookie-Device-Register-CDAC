import React, { useEffect, useRef, useState } from "react";
import AuthPanel from "./AuthPanel";
import Account from "./Account";
import Shapes from "./Shapes";
import { fetchMe, logout } from "./api";

/**
 * The top-level component. It only does three things:
 *
 *   1. On first load, ask "/api/me" -> does this browser's session id resolve
 *      to a live session on the server?
 *   2. Keep track of who is signed in.
 *   3. Show either the sign-in panel or the account panel.
 *
 * Step 1 is what keeps you signed in. Refresh the page, close the tab, restart
 * the machine — the cookie comes back, the server resolves it, and you land on
 * the account screen without typing anything. For up to 30 days, or until you
 * sign out.
 */
export default function App() {
  const [loading, setLoading] = useState(true);

  // Who is signed in (null = nobody). Set from the session on load, and from
  // the login response after signing in.
  const [user, setUser] = useState(null);

  /**
   * Guards against React 18's StrictMode, which deliberately runs every effect
   * twice in development to flush out non-idempotent ones.
   *
   * Ours genuinely is not idempotent — /api/me increments the visit counter on
   * the server — so without this guard every refresh would count as two visits
   * while developing, and as one in a production build. The ref makes both
   * behave the same.
   */
  const asked = useRef(false);

  // ---- Step 1: resolve the session id as soon as the page opens ----
  useEffect(() => {
    if (asked.current) return;
    asked.current = true;

    fetchMe()
      .then((data) => {
        if (data.found) {
          // A live session. Straight to the account screen — no password, no
          // sign-in form. THIS is the line that makes a refresh keep you in.
          setUser(data);
        }
      })
      .catch(() => {
        /* backend not running yet — just show a clean sign-in form */
      })
      .finally(() => setLoading(false));
  }, []);

  const handleSignedIn = (data) => {
    setUser(data);
  };

  /**
   * Sign out, for real.
   *
   * This has to reach the server. Clearing React state alone would do nothing:
   * the session row would still be alive, so the very next refresh would call
   * /api/me, get a valid session back, and sign you straight back in.
   */
  const handleSignOut = async () => {
    try {
      await logout();
    } catch (e) {
      /* the cookie is cleared by the response either way */
    }
    setUser(null);
  };

  if (loading) {
    return (
      <div className="loading">
        {/* three blocks, not a smooth circle — the theme has no curves */}
        <div className="spinner">
          <i />
          <i />
          <i />
        </div>
      </div>
    );
  }

  return (
    <div className="stage">
      {/* flat outlined shapes drifting behind the panel — draggable */}
      <Shapes />

      {user ? (
        <Account user={user} onSignOut={handleSignOut} />
      ) : (
        <AuthPanel onSignedIn={handleSignedIn} />
      )}
    </div>
  );
}
