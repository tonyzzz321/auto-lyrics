package com.autolyrics.auto

import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Identity of the Android Auto head unit currently driving the browse
 * service. Gearhead gives no device serial, but every host calls
 * onGetRoot with a model/build-specific root-hints bundle; hashing that
 * bundle yields a stable per-head-unit fingerprint. Two identical
 * head-unit models share a fingerprint, which is benign: they share a
 * viewport, the thing the fingerprint keys per-car calibration on.
 */
object AaHost {

    /** Global (pre-per-car) edge default; also seeds each car on sight. */
    const val EDGE_PREF_KEY = "aa_karaoke_edge"

    /** Fingerprint of the connected host's root hints; null when no
     *  hints have arrived for the current connection. */
    @Volatile
    var fingerprint: String? = null

    /** Latest BrowserIconSize hint of the connected host, or -1. */
    @Volatile
    var iconSizePx: Int = -1

    fun fingerprintOf(hints: Map<String, String?>): String? {
        val usable = hints.filterValues { it != null }
        if (usable.isEmpty()) return null
        val joined = usable.toSortedMap()
            .entries.joinToString("\u0001") { (key, value) -> "$key\u0000$value" }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun edge(prefs: SharedPreferences): Int {
        val fingerprint = fingerprint
        val global = prefs.getInt(EDGE_PREF_KEY, 0)
        if (fingerprint == null) return global
        return prefs.getInt("$EDGE_PREF_KEY:$fingerprint", global)
    }

    fun putEdge(prefs: SharedPreferences, value: Int) {
        val fingerprint = fingerprint
        val key = if (fingerprint == null) EDGE_PREF_KEY else "$EDGE_PREF_KEY:$fingerprint"
        prefs.edit().putInt(key, value).apply()
    }

    /** What the settings row is calibrating, e.g. "this car (9f2c1a)". */
    fun describe(): String {
        val fingerprint = fingerprint
        return when {
            fingerprint != null && iconSizePx > 0 -> "this car ($fingerprint · icon ${iconSizePx}px)"
            fingerprint != null -> "this car ($fingerprint)"
            else -> "all head units"
        }
    }

    fun onConnectionChanged(connected: Boolean) {
        if (!connected) {
            fingerprint = null
            iconSizePx = -1
        }
    }
}
