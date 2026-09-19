package com.solar.launcher;

/**
 * Allocation-free flywheel model for the Y1/Y2 rotary wheel.
 *
 * <p>Accelerate on rapid notches; hard-reset after a short idle (finger parked / let go).
 * Reverse direction kills momentum. Never continues emitting steps without a new KEY notch —
 * residual “coast after release” is a bug (coalescer backlog), not this model’s job.
 * Used by list coalescer + home/settings focus accel.
 */
public final class WheelPhysics {
    /**
     * Gap after last notch that zeros momentum (finger parked / let go).
     * 2026-07-17 — 110 ms (was 150): after a long track-list spin, late events must not
     * inherit multi-row flywheel. Still longer than a deliberate slow notch (~150–200 ms).
     */
    public static final long RESET_NANOS = 110_000_000L;
    /**
     * Velocity at which section/letter jump mode engages.
     * Must sit below steady-state spin (impulse / (1 − decay@~40ms)) so rapid dial
     * actually enters letter-jump on large libraries instead of plateauing forever.
     * 2026-07-17 — 3.05 with RESET=110ms + impulse 1.28: steady ~3.3 so letter-jump engages.
     */
    public static final float SECTION_THRESHOLD = 3.05f;
    /**
     * 2026-09-19 — iPod-style continuous scroll. A pause this long, or a
     * reversal, ends a spin; spinning this long in one direction makes it
     * {@link Result#sustained}, and long lists switch to letter (or page) jumps
     * until the finger stops. Speed alone (SECTION_THRESHOLD) needs notches
     * ~40 ms apart, which a steady thumb on the Y1 wheel rarely reaches.
     */
    public static final long SPIN_GAP_NANOS = 400_000_000L;
    public static final long SUSTAIN_NANOS = 1_200_000_000L;
    /** Max rows per KEY notch before section mode (keep modest to limit backlog). */
    public static final int MAX_ROW_STEPS = 4;
    /**
     * Impulse added each notch (higher = snappier ramp).
     * 2026-07-19 — Mic boost removed; plain KEY impulse only.
     */
    public static final float NOTCH_IMPULSE = 1.28f;

    private static final float[] DECAY = {
            1.00000000f, 0.978f, 0.956f, 0.935f, 0.914f, 0.894f, 0.874f, 0.855f,
            0.836f, 0.818f, 0.800f, 0.782f, 0.765f, 0.748f, 0.732f, 0.716f,
            0.700f, 0.685f, 0.670f, 0.655f, 0.641f, 0.627f, 0.613f, 0.600f,
            0.587f, 0.574f, 0.561f, 0.549f, 0.537f, 0.525f, 0.514f, 0.503f,
            0.492f, 0.481f, 0.471f, 0.460f, 0.450f, 0.440f, 0.431f, 0.421f,
            0.412f, 0.403f, 0.394f, 0.386f, 0.377f, 0.369f, 0.361f, 0.353f,
            0.345f, 0.338f, 0.330f, 0.323f, 0.316f, 0.309f, 0.302f, 0.296f,
            0.289f, 0.283f, 0.277f, 0.271f, 0.265f, 0.259f, 0.254f, 0.248f
    };

    private float velocity;
    private long lastNanos;
    private int lastDirection;
    private long spinStartNanos;

    public Result tick(long nowNanos, int direction, Result out) {
        if (out == null) out = new Result();
        long elapsed = lastNanos == 0L ? RESET_NANOS : Math.max(0L, nowNanos - lastNanos);
        if (direction == 0) {
            // Idle sample: decay stored momentum without emitting steps
            if (elapsed >= RESET_NANOS) {
                velocity = 0f;
                lastDirection = 0;
            } else if (velocity > 0f) {
                velocity *= decay(elapsed);
                if (velocity < 0.08f) velocity = 0f;
            }
            out.set(0, false, velocity, false);
            return out;
        }
        long gap = lastNanos == 0L ? Long.MAX_VALUE : Math.max(0L, nowNanos - lastNanos);
        if (spinStartNanos == 0L || gap >= SPIN_GAP_NANOS
                || (lastDirection != 0 && direction != lastDirection)) {
            spinStartNanos = nowNanos;
        }
        boolean sustained = nowNanos - spinStartNanos >= SUSTAIN_NANOS;
        // Hard stop residual when finger reverses or paused
        if (elapsed >= RESET_NANOS || (lastDirection != 0 && direction != lastDirection)) {
            velocity = 0f;
        } else {
            velocity *= decay(elapsed);
        }
        // KEY owns direction and impulse (2026-07-19 — no mic multiplier).
        velocity += NOTCH_IMPULSE;
        lastNanos = nowNanos;
        lastDirection = direction;
        boolean section = velocity >= SECTION_THRESHOLD;
        int steps = section ? 0 : Math.max(1, Math.min(MAX_ROW_STEPS, (int) velocity));
        out.set(steps, section, velocity, sustained);
        return out;
    }

    /**
     * Signed multi-step for short menus (home/settings): section → max jump, else 1..MAX.
     */
    public int signedMenuSteps(long nowNanos, int direction, Result scratch) {
        if (direction == 0) return 0;
        tick(nowNanos, direction, scratch);
        if (scratch.sectionJump) {
            return direction * MAX_ROW_STEPS;
        }
        return direction * Math.max(1, scratch.rowSteps);
    }

    public void reset() {
        velocity = 0f;
        lastNanos = 0L;
        lastDirection = 0;
        spinStartNanos = 0L;
    }

    public float velocity() {
        return velocity;
    }

    /**
     * 2026-07-18 — True while flywheel still has momentum (Rockbox REPEAT — no wrap).
     * Layman: finger is still spinning / just spun — don’t jump from last row to first.
     * Technical: velocity above creep after a KEY notch within RESET window.
     */
    public boolean isInAccel() {
        return velocity >= 0.35f || lastDirection != 0 && velocity > 0.08f;
    }

    /**
     * 2026-07-18 — Accel or multi-row stride — menus must clamp, not wrap.
     */
    public boolean suppressWrapAround() {
        return isInAccel() || velocity >= SECTION_THRESHOLD * 0.5f;
    }

    static float decay(long elapsedNanos) {
        if (elapsedNanos <= 0L) return 1f;
        if (elapsedNanos >= RESET_NANOS) return 0f;
        float p = elapsedNanos * (DECAY.length - 1f) / RESET_NANOS;
        int lo = (int) p;
        int hi = Math.min(DECAY.length - 1, lo + 1);
        float f = p - lo;
        return DECAY[lo] + (DECAY[hi] - DECAY[lo]) * f;
    }

    public static final class Result {
        public int rowSteps;
        public boolean sectionJump;
        public float velocity;
        /** Spinning one way for SUSTAIN_NANOS without a SPIN_GAP_NANOS pause. */
        public boolean sustained;

        private void set(int steps, boolean section, float value, boolean held) {
            rowSteps = steps;
            sectionJump = section;
            velocity = value;
            sustained = held;
        }
    }
}
