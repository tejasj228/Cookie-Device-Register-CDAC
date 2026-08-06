import React from "react";
import { Button } from "antd";
import AdminPanel from "./AdminPanel";

/**
 * Shown after a successful sign-in.
 *
 * Deliberately minimal: the signed-in person's name and email, and nothing else.
 *
 * Worth knowing where those two values came from — they arrived as JSON from
 * /api/login or /api/me, which read them off a row in the database. Neither was
 * in the cookie. The cookie held one random id and nothing more.
 *
 * "Sign out" is a real logout: it deletes the session row on the server, which
 * instantly invalidates that cookie everywhere, including any copy of it
 * somebody else may have taken. It does NOT clear the device cookie — that one
 * identifies the workstation, and the workstation has not gone anywhere.
 *
 * Admins get one extra thing: the workstation list. The `role` in the response
 * decides whether it is drawn, and it decides nothing else — every request the
 * panel makes is authorised again on the server, from the database. A worker
 * who forces this branch open in DevTools gets a panel of failing buttons,
 * which is the correct outcome: hiding it is a courtesy to workers, not a lock
 * against them.
 */
export default function Account({ user, onSignOut }) {
  const initial = user.fullName.charAt(0).toUpperCase();
  const isAdmin = user.role === "ADMIN";

  return (
    <div className={isAdmin ? "panel is-admin" : "panel"}>
      <div className="panel-body">
        <div className="account-head">
          <div className="account-avatar">{initial}</div>
          <div className="account-id">
            <h1 className="account-name">{user.fullName}</h1>
            <p className="account-status">
              {isAdmin ? "Administrator" : "Signed in"}
            </p>
            <p className="account-email">{user.email}</p>
          </div>
        </div>

        {isAdmin && <AdminPanel />}

        <div className="actions">
          <Button block danger onClick={onSignOut}>
            Sign out
          </Button>
        </div>
      </div>
    </div>
  );
}
