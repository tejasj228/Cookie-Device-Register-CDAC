package com.example.cookiedemo;

/**
 * What an account is allowed to be.
 *
 * The distinction exists for exactly one reason: device binding applies to
 * WORKER accounts and not to ADMIN ones. A worker is pinned to the browser they
 * first signed in from; an admin has to be able to walk up to any machine in
 * the office — including a workstation already claimed by a worker — and get in,
 * because an admin is the only person who can undo a binding.
 *
 * Stored as a string in the database rather than an ordinal. An ordinal would
 * mean the meaning of the column silently changes the day somebody reorders
 * this enum or inserts a value in the middle, which is the kind of bug that
 * turns every worker into an admin during a routine refactor.
 */
public enum Role {

    /** Bound to one browser. The default for every account created through /api/register. */
    WORKER,

    /** Exempt from device binding, and the only role that can reset someone else's. */
    ADMIN;

    public boolean isAdmin() {
        return this == ADMIN;
    }
}
