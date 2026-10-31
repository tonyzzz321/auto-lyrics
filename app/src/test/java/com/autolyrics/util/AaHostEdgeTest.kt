package com.autolyrics.util

import android.content.SharedPreferences
import com.autolyrics.auto.AaHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue-5 regression: resetting the edge while connected must clear the
 * GLOBAL default too — the per-car key only answers while the fingerprint
 * lives, and every read after the identity drops falls back to the global.
 */
class AaHostEdgeTest {

    private class FakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?): String? =
            map[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as MutableSet<String>?) ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as Boolean? ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            l: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                also { map[key] = value }
            override fun putStringSet(
                key: String, value: MutableSet<String>?
            ): SharedPreferences.Editor = also { map[key] = value }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                also { map[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                also { map[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                also { map[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                also { map[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = also { map.remove(key) }
            override fun clear(): SharedPreferences.Editor = also { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }

    @After
    fun clearIdentity() {
        AaHost.fingerprint = null
    }

    @Test
    fun carCalibrationSeedsFromGlobalAndOverridesIt() {
        val prefs = FakePrefs()
        AaHost.fingerprint = null
        AaHost.putEdge(prefs, 6) // global default +6
        AaHost.fingerprint = "abcd"
        assertEquals(6, AaHost.edge(prefs)) // unseen car inherits the default
        AaHost.putEdge(prefs, 2)
        assertEquals(2, AaHost.edge(prefs)) // car value wins while connected
    }

    @Test
    fun pinnedColumnSurvivesUpgradesAndResetReleasesIt() {
        val prefs = FakePrefs()
        AaHost.fingerprint = "abcd"
        // First sight resolves the CURRENT model (icon 143 -> base 42, edge 0).
        assertEquals(42, AaHost.column(prefs, 143))
        // A column-model upgrade (or any restored delta pref) must NOT move
        // a calibrated car: the pinned resolution answers, not a recompute.
        prefs.edit().putInt("aa_karaoke_edge:abcd", 5).apply()
        assertEquals(42, AaHost.column(prefs, 143))
        // Moving the knob re-resolves against what this build renders.
        AaHost.putEdge(prefs, 5, iconPx = 143)
        assertEquals(47, AaHost.column(prefs, 143))
        // Reset drops the pin: control returns to the current model.
        AaHost.resetEdge(prefs, "abcd")
        assertEquals(42, AaHost.column(prefs, 143))
    }

    @Test
    fun hintLessCarKeepsPlainDeltaReadWithoutPinning() {
        val prefs = FakePrefs()
        AaHost.fingerprint = null
        prefs.edit().putInt("aa_karaoke_edge", 3).apply()
        assertEquals(45, AaHost.column(prefs, -1)) // fallback 42 + global edge 3
        // No identity to pin to: nothing is cached, the delta keeps answering.
        org.junit.Assert.assertFalse(prefs.contains("aa_karaoke_column"))
    }

    @Test
    fun resetClearsGlobalSoStaleEdgeCannotResurrect() {
        val prefs = FakePrefs()
        AaHost.fingerprint = "abcd"
        AaHost.putEdge(prefs, 6) // per-car calibration while connected
        assertEquals(6, AaHost.edge(prefs))

        AaHost.resetEdge(prefs, "abcd")
        assertEquals(0, AaHost.edge(prefs))
        // Identity drops (service restart / hint-less reconnect): the read
        // falls back to the GLOBAL — it must be zero too, not the pre-reset
        // value a per-car-only reset would have left behind.
        AaHost.fingerprint = null
        assertEquals(0, AaHost.edge(prefs))
        assertEquals(0, AaHost.globalEdge(prefs))
    }
}
