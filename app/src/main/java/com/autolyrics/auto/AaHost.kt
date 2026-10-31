package com.autolyrics.auto

import android.content.SharedPreferences
import com.autolyrics.util.AaKaraokeRows
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

    /** Resolved column cache: the edge delta is a nudge against the column
     *  MODEL, so any release that changes the model (icon-size derivation,
     *  clamp, margin) would silently move a calibrated car's right edge at
     *  the same pref value. The resolved column is therefore PINNED per
     *  fingerprint at calibration time; upgrades cannot re-base it, only
     *  moving the knob (or a reset) recomputes. */
    const val COLUMN_PREF_KEY = "aa_karaoke_column"

    /** Fallback column when a host sends no BrowserIconSize hint. */
    const val DEFAULT_COLUMN = 42

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

    /**
     * The live board column: the pinned per-car resolution when one exists
     * (immune to column-model upgrades shipped after the calibration),
     * otherwise computed from the current model and pinned for the car.
     * Hint-less hosts (no fingerprint) keep the plain delta read: there is
     * no identity to pin a column to.
     */
    fun column(prefs: SharedPreferences, iconPx: Int, fallbackColumn: Int = DEFAULT_COLUMN): Int {
        val fingerprint = fingerprint ?: return AaKaraokeRows.columnFromIconSize(
            iconPx, globalEdge(prefs), fallbackColumn
        )
        val key = "$COLUMN_PREF_KEY:$fingerprint"
        prefs.getInt(key, -1).takeIf { it > 0 }?.let { return it }
        val column = AaKaraokeRows.columnFromIconSize(iconPx, edge(prefs), fallbackColumn)
        prefs.edit().putInt(key, column).apply()
        return column
    }

    fun putEdge(prefs: SharedPreferences, value: Int, iconPx: Int = -1) {
        val fingerprint = fingerprint
        val key = if (fingerprint == null) EDGE_PREF_KEY else "$EDGE_PREF_KEY:$fingerprint"
        prefs.edit().putInt(key, value).apply()
        // Moving the knob RE-resolves the column under the current model:
        // the user is tuning against what this build renders, and the new
        // resolution is what stays pinned from now on.
        if (fingerprint != null && iconPx > 0) {
            prefs.edit().putInt(
                "$COLUMN_PREF_KEY:$fingerprint",
                AaKaraokeRows.columnFromIconSize(iconPx, value, DEFAULT_COLUMN)
            ).apply()
        }
    }

    /** Global default edge (the value a car without its own calibration
     *  inherits on sight). */
    fun globalEdge(prefs: SharedPreferences): Int = prefs.getInt(EDGE_PREF_KEY, 0)

    /**
     * Reset means "no nudge anywhere": a per-car zero would otherwise sit
     * beside a non-zero global and resurrect the old edge whenever the car
     * identity drops (service restart, reconnect with no hints), because
     * the car key only exists per fingerprint and the global is the
     * fallback for every un-keyed read.
     */
    fun resetEdge(prefs: SharedPreferences, fingerprint: String?) {
        val editor = prefs.edit().putInt(EDGE_PREF_KEY, 0)
        if (fingerprint != null) editor.putInt("$EDGE_PREF_KEY:$fingerprint", 0)
        // Drop the pinned resolutions too: a reset must hand control back
        // to the CURRENT model, not resurrect a stale pinned column.
        editor.remove(COLUMN_PREF_KEY)
        if (fingerprint != null) editor.remove("$COLUMN_PREF_KEY:$fingerprint")
        editor.apply()
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
