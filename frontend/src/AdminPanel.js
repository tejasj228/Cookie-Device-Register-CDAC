import React, { useCallback, useEffect, useState } from "react";
import { Button, Alert, Popconfirm } from "antd";
import { fetchWorkers, resetWorkerDevice } from "./api";

/**
 * The admin's view of the workforce: who is bound to a browser, and a button to
 * unbind them.
 *
 * ---------------------------------------------------------------------------
 *  Why this screen has to exist
 * ---------------------------------------------------------------------------
 *  Device binding is deliberately a one-way door for the worker. They cannot
 *  move themselves to a new browser — if they could, the rule would enforce
 *  nothing. So every ordinary event that clears a cookie (a reimaged PC, a
 *  swapped laptop, an IT department running Clear Browsing Data) leaves someone
 *  locked out with no way back in on their own.
 *
 *  That is not a flaw to be apologised for; it is the design working. But it
 *  does mean the reset has to be genuinely easy to find and do, or the lockout
 *  becomes a support ticket that takes a day. Hence: one list, one button.
 *
 *  Note what the list does NOT show — the device token, or its hash. An admin
 *  needs to know THAT a worker is bound in order to decide whether to reset
 *  them. Nothing about the screen is improved by putting a live credential's
 *  fingerprint on it, and admin screens are the ones that end up screenshotted
 *  into support tickets.
 * ---------------------------------------------------------------------------
 */
export default function AdminPanel() {
  const [workers, setWorkers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [alert, setAlert] = useState(null); // { type, text }

  /** Which username has a reset in flight, so only that row shows a spinner. */
  const [resetting, setResetting] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchWorkers();
      setWorkers(data.workers || []);
    } catch (err) {
      setAlert({ type: "error", text: err.message });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const handleReset = async (username) => {
    setResetting(username);
    setAlert(null);
    try {
      const data = await resetWorkerDevice(username);
      setAlert({ type: "success", text: data.message });

      // Re-read the list from the server rather than patching it locally. The
      // server is the only thing that knows what actually happened, and a list
      // that disagrees with it is worse than a list that takes an extra moment.
      await load();
    } catch (err) {
      setAlert({ type: "error", text: err.message });
    } finally {
      setResetting(null);
    }
  };

  return (
    <div className="admin">
      <div className="admin-head">
        <h2 className="admin-title">Workstations</h2>
        <button className="admin-refresh" onClick={load} disabled={loading}>
          {loading ? "Loading" : "Refresh"}
        </button>
      </div>

      {alert && (
        <Alert
          type={alert.type}
          message={alert.text}
          showIcon
          closable
          onClose={() => setAlert(null)}
          style={{ marginBottom: 14 }}
        />
      )}

      {!loading && workers.length === 0 && (
        <p className="admin-empty">No worker accounts yet.</p>
      )}

      <ul className="worker-list">
        {workers.map((worker) => (
          <li className="worker" key={worker.username}>
            <div className="worker-id">
              <span className="worker-name">{worker.fullName}</span>
              <span className="worker-username">@{worker.username}</span>
            </div>

            <span
              className={
                worker.deviceRegistered ? "worker-tag is-bound" : "worker-tag"
              }
            >
              {worker.deviceRegistered ? "Bound" : "Unbound"}
            </span>

            {/*
              The button is only offered when there is something to undo.
              Resetting an unbound worker is harmless and the server accepts it,
              but a button that visibly does nothing teaches people to distrust
              the screen.
            */}
            {worker.deviceRegistered && (
              <Popconfirm
                title="Reset this workstation?"
                description={
                  `${worker.fullName} will be signed out, and the next browser ` +
                  `they sign in from becomes their registered one.`
                }
                okText="Reset"
                cancelText="Cancel"
                onConfirm={() => handleReset(worker.username)}
              >
                <Button
                  size="small"
                  danger
                  loading={resetting === worker.username}
                >
                  Reset
                </Button>
              </Popconfirm>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
