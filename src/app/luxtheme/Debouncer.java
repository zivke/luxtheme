package app.luxtheme;

/**
 * Decides when the theme should follow the light level.
 *
 * A reading on the other side of the threshold starts a countdown. The switch
 * happens when the countdown reaches the debounce time. Readings back on the
 * old side cancel it, but only once they have lasted for the grace time, so a
 * single stray sample does not throw away minutes of waiting.
 *
 * No Android dependencies, so it runs in a plain JVM test. All times are
 * milliseconds on one monotonic clock chosen by the caller.
 */
final class Debouncer {
    /** Side the theme was last set to: true = dark, null = not known. */
    private Boolean stable;
    /** Side waiting out the debounce period, or null. */
    private Boolean candidate;
    private long candidateSince;
    /** When readings went back to the stable side during a countdown, or -1. */
    private long interruptedSince = -1;

    void setStable(Boolean dark) {
        stable = dark;
        candidate = null;
        interruptedSince = -1;
    }

    /** Puts back a countdown that was saved with the getters below. */
    void restore(Boolean stable, Boolean candidate, long candidateSince, long interruptedSince) {
        this.stable = stable;
        this.candidate = candidate;
        this.candidateSince = candidateSince;
        this.interruptedSince = candidate == null ? -1 : interruptedSince;
    }

    /**
     * Feeds one reading.
     *
     * @return the side to switch to now, or null if nothing should happen yet
     */
    Boolean update(boolean dark, long nowMs, long debounceMs, long graceMs) {
        // An interruption that already lasted the grace time cancelled the countdown, even
        // if no timer ran to notice (the device slept) and this reading is back on its side.
        if (interruptedSince >= 0 && nowMs - interruptedSince >= graceMs) {
            candidate = null;
            interruptedSince = -1;
        }
        if (stable != null && stable == dark) {
            if (candidate != null && interruptedSince < 0) {
                interruptedSince = nowMs;
            }
        } else {
            interruptedSince = -1;
            if (candidate == null || candidate != dark) {
                candidate = dark;
                candidateSince = nowMs;
            }
        }
        return check(nowMs, debounceMs, graceMs);
    }

    /** Same as {@link #update} but without a new reading, for the timer. */
    Boolean check(long nowMs, long debounceMs, long graceMs) {
        if (candidate == null) {
            return null;
        }
        if (interruptedSince >= 0) {
            // The latest reading is on the old side: never switch now, cancel once it has lasted.
            if (nowMs - interruptedSince >= graceMs) {
                candidate = null;
                interruptedSince = -1;
            }
            return null;
        }
        if (nowMs - candidateSince < debounceMs) {
            return null;
        }
        stable = candidate;
        candidate = null;
        return stable;
    }

    Boolean stable() {
        return stable;
    }

    /** Side currently counting down, or null. */
    Boolean pending() {
        return candidate;
    }

    long pendingSince() {
        return candidateSince;
    }

    /** When the latest readings left the pending side, or -1. */
    long interruptedSince() {
        return interruptedSince;
    }

    boolean interrupted() {
        return interruptedSince >= 0;
    }

    /** Milliseconds until the pending switch, or -1 if none is pending. */
    long remaining(long nowMs, long debounceMs) {
        if (candidate == null) {
            return -1;
        }
        return Math.max(0, debounceMs - (nowMs - candidateSince));
    }

    /** Milliseconds until {@link #check} can next change anything, or -1 if nothing is pending. */
    long nextCheckIn(long nowMs, long debounceMs, long graceMs) {
        if (candidate == null) {
            return -1;
        }
        if (interruptedSince >= 0) {
            return Math.max(0, graceMs - (nowMs - interruptedSince));
        }
        return remaining(nowMs, debounceMs);
    }
}
