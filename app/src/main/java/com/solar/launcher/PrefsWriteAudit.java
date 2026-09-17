package com.solar.launcher;

import android.content.SharedPreferences;
import android.util.Log;

import java.util.Map;
import java.util.Set;

/**
 * 2026-09-16 — Makes a SOLAR_SETTINGS writer name itself.
 *
 * MODE_PRIVATE prefs are a per-process cached map, and commit() rewrites the whole
 * file from it. A process that has only ever touched a handful of keys therefore
 * writes a file with only a handful of keys, and every setting the user has is gone.
 * That is the settings wipe: on 2026-09-16 a force-stop took the file from 452 keys
 * to 30, and the 30 were one process's working set -- theme, home menu, clock, USB
 * session -- not a random subset.
 *
 * Which process, though, is a question 39 classes and 166 getSharedPreferences call
 * sites cannot answer by reading. So every commit says who it is, how many keys it is
 * about to write, and which line asked. A line reading {@code keys=30} beside a line
 * reading {@code keys=452} is the whole diagnosis.
 *
 * Pull with: {@code adb logcat -s SolarPrefsAudit:W}
 *
 * Diagnostic only -- it changes nothing about what is written. Reversal: drop the
 * getSharedPreferences override in {@link SolarApplication} and delete this file.
 */
public final class PrefsWriteAudit {
    public static final String TAG = "SolarPrefsAudit";

    /**
     * Below this, a write is worth shouting about. The healthy file has been in the
     * hundreds all week; the wipes land in the dozens.
     */
    private static final int SUSPICIOUS_KEYS = 120;

    private PrefsWriteAudit() {}

    /**
     * Audited SOLAR_SETTINGS for a helper handed an arbitrary Context.
     *
     * Routing through the application context is what puts the write in the log, and
     * it is a no-op for behaviour: within a process every Context returns the same
     * SharedPreferencesImpl for a given name, so this is the same map either way.
     */
    public static android.content.SharedPreferences get(
            android.content.Context context, String name) {
        if (context == null) return null;
        android.content.Context app = context.getApplicationContext();
        return (app != null ? app : context)
                .getSharedPreferences(name, android.content.Context.MODE_PRIVATE);
    }

    public static SharedPreferences wrap(SharedPreferences real, String name) {
        if (real == null || real instanceof Audited) return real;
        return new Audited(real, name);
    }

    /** The frame that asked to commit, skipping this file and the framework. */
    private static String caller() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (StackTraceElement e : st) {
                String cls = e.getClassName();
                if (cls.startsWith("dalvik.") || cls.startsWith("java.lang.Thread")) continue;
                if (cls.equals(PrefsWriteAudit.class.getName())
                        || cls.startsWith(PrefsWriteAudit.class.getName() + "$")) continue;
                if (sb.length() > 0) sb.append(" < ");
                int dot = cls.lastIndexOf('.');
                sb.append(dot >= 0 ? cls.substring(dot + 1) : cls)
                  .append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
                if (++shown == 4) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void report(SharedPreferences real, String name, String how, int edits) {
        int keys;
        try {
            Map<String, ?> all = real.getAll();
            keys = all != null ? all.size() : -1;
        } catch (Throwable t) {
            keys = -1;
        }
        String line = name + " " + how + " keys=" + keys + " edits=" + edits
                + " proc=" + SolarProcess.name()
                + " pid=" + android.os.Process.myPid()
                + " at " + caller();
        // A small map is the wipe happening. Anything else is just bookkeeping.
        if (keys >= 0 && keys < SUSPICIOUS_KEYS) {
            Log.w(TAG, "SMALL WRITE -- " + line);
        } else {
            Log.i(TAG, line);
        }
    }

    /** Pass-through prefs whose editors announce their commits. */
    private static final class Audited implements SharedPreferences {
        private final SharedPreferences real;
        private final String name;

        Audited(SharedPreferences real, String name) {
            this.real = real;
            this.name = name;
        }

        @Override public Map<String, ?> getAll() { return real.getAll(); }
        @Override public String getString(String k, String d) { return real.getString(k, d); }
        @Override public Set<String> getStringSet(String k, Set<String> d) {
            return real.getStringSet(k, d);
        }
        @Override public int getInt(String k, int d) { return real.getInt(k, d); }
        @Override public long getLong(String k, long d) { return real.getLong(k, d); }
        @Override public float getFloat(String k, float d) { return real.getFloat(k, d); }
        @Override public boolean getBoolean(String k, boolean d) { return real.getBoolean(k, d); }
        @Override public boolean contains(String k) { return real.contains(k); }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {
            real.registerOnSharedPreferenceChangeListener(l);
        }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {
            real.unregisterOnSharedPreferenceChangeListener(l);
        }
        @Override public Editor edit() { return new AuditedEditor(real, real.edit(), name); }
    }

    /** Counts what an edit touched, so a one-key edit that rewrites the file is visible. */
    private static final class AuditedEditor implements SharedPreferences.Editor {
        private final SharedPreferences real;
        private final SharedPreferences.Editor inner;
        private final String name;
        private int edits;

        AuditedEditor(SharedPreferences real, SharedPreferences.Editor inner, String name) {
            this.real = real;
            this.inner = inner;
            this.name = name;
        }

        @Override public SharedPreferences.Editor putString(String k, String v) {
            edits++; inner.putString(k, v); return this;
        }
        @Override public SharedPreferences.Editor putStringSet(String k, Set<String> v) {
            edits++; inner.putStringSet(k, v); return this;
        }
        @Override public SharedPreferences.Editor putInt(String k, int v) {
            edits++; inner.putInt(k, v); return this;
        }
        @Override public SharedPreferences.Editor putLong(String k, long v) {
            edits++; inner.putLong(k, v); return this;
        }
        @Override public SharedPreferences.Editor putFloat(String k, float v) {
            edits++; inner.putFloat(k, v); return this;
        }
        @Override public SharedPreferences.Editor putBoolean(String k, boolean v) {
            edits++; inner.putBoolean(k, v); return this;
        }
        @Override public SharedPreferences.Editor remove(String k) {
            edits++; inner.remove(k); return this;
        }
        @Override public SharedPreferences.Editor clear() {
            edits++; inner.clear(); return this;
        }

        @Override public boolean commit() {
            report(real, name, "commit", edits);
            return inner.commit();
        }

        @Override public void apply() {
            report(real, name, "apply", edits);
            inner.apply();
        }
    }
}
