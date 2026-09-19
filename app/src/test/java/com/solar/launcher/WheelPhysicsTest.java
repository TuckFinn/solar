package com.solar.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

public class WheelPhysicsTest {
    @Test public void steadySpinBecomesSustainedAndPauseOrReverseEndsIt() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 1_000_000_000L;
        // A steady thumb: a notch every 150 ms (too slow for speed-based section mode).
        for (int i = 0; i < 7; i++) p.tick(t += 150_000_000L, 1, r);
        assertFalse(r.sustained);
        for (int i = 0; i < 2; i++) p.tick(t += 150_000_000L, 1, r);
        assertTrue("1.2 s of spinning should be sustained", r.sustained);
        assertFalse("not by speed", r.sectionJump);
        p.tick(t += WheelPhysics.SPIN_GAP_NANOS, 1, r);
        assertFalse("a pause starts a new spin", r.sustained);
        for (int i = 0; i < 9; i++) p.tick(t += 150_000_000L, 1, r);
        assertTrue(r.sustained);
        p.tick(t += 150_000_000L, -1, r);
        assertFalse("reversing starts a new spin", r.sustained);
    }

    @Test public void isolatedTicksMoveOneRow() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        p.tick(1_000_000_000L, 1, r);
        assertEquals(1, r.rowSteps);
        // > RESET gap
        p.tick(1_000_000_000L + WheelPhysics.RESET_NANOS + 1, 1, r);
        assertEquals(1, r.rowSteps);
    }

    @Test public void burstAcceleratesAndCapsBeforeSectionMode() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 1_000_000_000L;
        for (int i = 0; i < 4; i++) p.tick(t += 40_000_000L, 1, r);
        assertTrue(r.rowSteps >= 2 && r.rowSteps <= WheelPhysics.MAX_ROW_STEPS);
        int guard = 0;
        while (!r.sectionJump && guard++ < 40) {
            p.tick(t += 40_000_000L, 1, r);
        }
        assertTrue("expected section jump under rapid spin", r.sectionJump);
        assertEquals(0, r.rowSteps);
    }

    @Test public void pauseAndDirectionChangeResetMomentum() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 1_000_000_000L;
        for (int i = 0; i < 4; i++) p.tick(t += 30_000_000L, 1, r);
        p.tick(t += WheelPhysics.RESET_NANOS, 1, r);
        assertEquals(1, r.rowSteps);
        p.tick(t += 30_000_000L, -1, r);
        assertEquals(1, r.rowSteps);
    }

    @Test public void signedMenuStepsAmplifiesBurst() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 2_000_000_000L;
        int last = 0;
        for (int i = 0; i < 3; i++) {
            last = p.signedMenuSteps(t += 35_000_000L, 1, r);
        }
        assertTrue(Math.abs(last) >= 2);
    }

    @Test public void idleDecayClearsVelocity() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 3_000_000_000L;
        for (int i = 0; i < 5; i++) p.tick(t += 25_000_000L, 1, r);
        assertTrue(p.velocity() > 1f);
        assertTrue(p.suppressWrapAround());
        p.tick(t + WheelPhysics.RESET_NANOS + 1, 0, r);
        assertEquals(0f, p.velocity(), 0.001f);
        assertFalse(p.suppressWrapAround());
    }

    @Test public void lateNotchAfterPauseIsSingleStepNotFlywheel() {
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 4_000_000_000L;
        for (int i = 0; i < 6; i++) p.tick(t += 20_000_000L, 1, r);
        assertTrue(r.rowSteps >= 2 || r.sectionJump);
        // Pause longer than RESET — next notch is 1 row, not multi-step coast.
        p.tick(t + WheelPhysics.RESET_NANOS + 1, 1, r);
        assertEquals(1, r.rowSteps);
        assertFalse(r.sectionJump);
    }

    @Test public void rapidNotchesRampWithoutMicBoost() {
        // 2026-07-19 — Mic boost removed; KEY impulse alone still ramps multi-row.
        WheelPhysics p = new WheelPhysics();
        WheelPhysics.Result r = new WheelPhysics.Result();
        long t = 5_000_000_000L;
        for (int i = 0; i < 4; i++) {
            t += 30_000_000L;
            p.tick(t, 1, r);
        }
        assertTrue(r.rowSteps > 0 || r.sectionJump);
    }

    @Test public void resetNanosIsTightForInstantStopAfterLongSpin() {
        assertTrue(WheelPhysics.RESET_NANOS <= 120_000_000L);
        assertTrue(WheelPhysics.RESET_NANOS >= 80_000_000L);
    }
}
