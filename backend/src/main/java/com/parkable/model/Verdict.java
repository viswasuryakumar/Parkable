package com.parkable.model;

/** Final answer to "can I park here right now?". */
public enum Verdict {
    PARKABLE,
    NOT_PARKABLE,
    /** Ambiguous: directional conflict, unverifiable permit, etc. */
    DEPENDS
}
