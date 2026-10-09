package app.luxtheme;

/** Plain JVM test, no Android needed: make test */
public class DebouncerTest {
    static final long S = 1_000, MIN = 60 * S, DB = 5 * MIN, GRACE = 3 * S;

    public static void main(String[] args) {
        basics();
        briefInterruptions();
        strictWithoutGrace();
        restoreAfterRestart();
        System.out.println("Debouncer: all checks passed");
    }

    static void basics() {
        Debouncer d = new Debouncer();
        d.setStable(false);                                   // theme is light

        // Bright readings while light: nothing pending.
        check(d.update(false, 0, DB, GRACE) == null && d.pending() == null, "bright stays idle");
        check(d.nextCheckIn(0, DB, GRACE) == -1, "no timer when idle");

        // Goes dark: countdown starts, nothing switches before 5 minutes.
        check(d.update(true, 1 * MIN, DB, GRACE) == null, "no switch at once");
        check(d.remaining(1 * MIN, DB) == DB, "full countdown");
        check(d.update(true, 3 * MIN, DB, GRACE) == null, "no switch at 2 min");
        check(d.remaining(3 * MIN, DB) == 3 * MIN, "3 min left");
        check(d.nextCheckIn(3 * MIN, DB, GRACE) == 3 * MIN, "timer set to the deadline");
        check(d.check(6 * MIN - 1, DB, GRACE) == null, "no switch 1 ms early");
        check(Boolean.TRUE.equals(d.update(true, 6 * MIN, DB, GRACE)), "switch to dark after 5 min");
        check(d.pending() == null && d.remaining(6 * MIN, DB) == -1, "idle after switch");

        // Timer path: steady bright level with no further sensor events.
        check(d.update(false, 20 * MIN, DB, GRACE) == null, "bright starts countdown");
        check(d.check(24 * MIN, DB, GRACE) == null, "timer early");
        check(Boolean.FALSE.equals(d.check(25 * MIN, DB, GRACE)), "timer switches to light");
        check(d.check(26 * MIN, DB, GRACE) == null, "fires once");

        // After reset the same side is attempted again (retry after a failed switch).
        d.reset();
        check(d.update(false, 31 * MIN, DB, GRACE) == null, "retry waits");
        check(Boolean.FALSE.equals(d.check(36 * MIN, DB, GRACE)), "retry fires");
    }

    static void briefInterruptions() {
        Debouncer d = new Debouncer();
        d.setStable(false);
        d.update(true, 0, DB, GRACE);                         // countdown to dark from t=0

        // A stray bright sample shorter than the grace time does not cancel.
        check(d.update(false, 2 * MIN, DB, GRACE) == null && d.interrupted(), "blip noticed");
        check(Boolean.TRUE.equals(d.pending()), "blip keeps the countdown");
        check(d.nextCheckIn(2 * MIN, DB, GRACE) == GRACE, "timer set to the end of the grace time");
        check(d.update(true, 2 * MIN + 1 * S, DB, GRACE) == null && !d.interrupted(), "blip over");
        check(d.remaining(2 * MIN + 1 * S, DB) == 3 * MIN - 1 * S, "countdown kept its start time");

        // The deadline passing during a blip does not switch while the reading is bright...
        check(d.update(false, 5 * MIN - 1 * S, DB, GRACE) == null, "second blip");
        check(d.check(5 * MIN, DB, GRACE) == null, "no switch while interrupted");
        // ...but does as soon as the reading is dark again.
        check(Boolean.TRUE.equals(d.update(true, 5 * MIN + 1 * S, DB, GRACE)), "switch once the blip ends");

        // Bright for the whole grace time cancels, by a reading or by the timer.
        d.setStable(false);
        d.update(true, 10 * MIN, DB, GRACE);
        d.update(false, 11 * MIN, DB, GRACE);
        check(d.update(false, 11 * MIN + 2 * S, DB, GRACE) == null && d.pending() != null, "still within grace");
        check(d.update(false, 11 * MIN + 3 * S, DB, GRACE) == null && d.pending() == null, "reading cancels");
        d.update(true, 12 * MIN, DB, GRACE);
        d.update(false, 13 * MIN, DB, GRACE);
        check(d.check(13 * MIN + 3 * S, DB, GRACE) == null && d.pending() == null, "timer cancels");
        // A cancelled countdown starts again from the full time.
        d.update(true, 14 * MIN, DB, GRACE);
        check(d.remaining(14 * MIN, DB) == DB, "restart from full after a real cancel");
    }

    static void strictWithoutGrace() {
        Debouncer d = new Debouncer();
        d.setStable(false);
        d.update(true, 0, DB, 0);
        check(d.update(false, 1 * MIN, DB, 0) == null && d.pending() == null, "grace 0: one reading cancels");
        // Zero debounce switches on the first reading.
        check(Boolean.TRUE.equals(d.update(true, 2 * MIN, 0, 0)), "zero debounce");
    }

    static void restoreAfterRestart() {
        Debouncer old = new Debouncer();
        old.setStable(false);
        old.update(true, 1 * MIN, DB, GRACE);

        // A new instance, as after the process was killed, carries on with the saved countdown.
        Debouncer d = new Debouncer();
        d.restore(old.stable(), old.pending(), old.pendingSince(), old.interruptedSince());
        check(d.remaining(4 * MIN, DB) == 2 * MIN, "restored countdown keeps its start time");
        check(d.update(true, 4 * MIN, DB, GRACE) == null, "not yet");
        check(Boolean.TRUE.equals(d.update(true, 6 * MIN, DB, GRACE)), "restored countdown completes");

        // Restarted after the deadline: switches at the first matching reading.
        Debouncer late = new Debouncer();
        late.restore(false, true, 1 * MIN, -1);
        check(Boolean.TRUE.equals(late.update(true, 30 * MIN, DB, GRACE)), "late restart switches at once");
        // ...but not if the light has changed back meanwhile.
        Debouncer changed = new Debouncer();
        changed.restore(false, true, 1 * MIN, -1);
        check(changed.update(false, 30 * MIN, DB, GRACE) == null, "late restart, light is back: no switch");
        check(changed.check(30 * MIN + 3 * S, DB, GRACE) == null && changed.pending() == null, "and it cancels");
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
