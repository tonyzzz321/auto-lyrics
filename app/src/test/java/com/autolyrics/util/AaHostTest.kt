package com.autolyrics.util

import com.autolyrics.auto.AaHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AaHostTest {

    @Test
    fun fingerprintIsStableAndOrderIndependent() {
        val a = AaHost.fingerprintOf(
            mapOf(
                "com.google.android.gms.car.media.BrowserIconSize" to "56",
                "android.media.browse.KEY_ROOT_CHILDREN_LIMIT" to "4"
            )
        )
        val b = AaHost.fingerprintOf(
            mapOf(
                "android.media.browse.KEY_ROOT_CHILDREN_LIMIT" to "4",
                "com.google.android.gms.car.media.BrowserIconSize" to "56"
            )
        )
        assertEquals(a, b)
        assertEquals(12, a?.length)
    }

    @Test
    fun fingerprintSeparatesHostsWithDifferentHints() {
        val dhu = AaHost.fingerprintOf(mapOf("icon" to "143"))
        val car = AaHost.fingerprintOf(mapOf("icon" to "56"))
        val extraKey = AaHost.fingerprintOf(mapOf("icon" to "56", "host" to "2"))
        assertNotEquals(dhu, car)
        assertNotEquals(car, extraKey)
    }

    @Test
    fun hintlessHostsHaveNoFingerprint() {
        assertNull(AaHost.fingerprintOf(emptyMap()))
        assertNull(AaHost.fingerprintOf(mapOf("style" to null)))
    }
}
