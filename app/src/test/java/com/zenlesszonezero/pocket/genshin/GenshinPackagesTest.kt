package com.zenlesszonezero.pocket.genshin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenshinPackagesTest {
    @Test
    fun `recognizes zenless zone zero packages`() {
        assertTrue(GenshinPackages.isGenshinPackage("com.miHoYo.zenlessZoneZero"))
        assertTrue(GenshinPackages.isGenshinPackage("com.HoYoverse.zenlessZoneZero"))
        assertTrue(GenshinPackages.isGenshinPackage("com.miHoYo.cloudgames.zenlessZoneZero"))
        assertFalse(GenshinPackages.isGenshinPackage("com.miHoYo.Yuanshen"))
        assertFalse(GenshinPackages.isGenshinPackage("com.zenlesszonezero.pocket"))
        assertFalse(GenshinPackages.isGenshinPackage(null))
    }

    @Test
    fun `prefers official china client when several are installed`() {
        val picked = GenshinPackages.pickPreferred(
            listOf(
                "com.HoYoverse.zenlessZoneZero",
                "com.miHoYo.zenlessZoneZero",
                "com.miHoYo.cloudgames.zenlessZoneZero",
            ),
        )
        assertEquals("com.miHoYo.zenlessZoneZero", picked)
    }

    @Test
    fun `falls back to the first recognized package`() {
        assertEquals(
            "com.miHoYo.cloudgames.zenlessZoneZero",
            GenshinPackages.pickPreferred(listOf("com.miHoYo.cloudgames.zenlessZoneZero")),
        )
        assertNull(GenshinPackages.pickPreferred(listOf("com.android.vending")))
    }

    @Test
    fun `auto launch only fires once when game is not already open`() {
        assertFalse(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = false,
                genshinInForeground = false,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertFalse(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = true,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertTrue(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = false,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertTrue(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = null,
                alreadyAttempted = false,
                allowed = true,
            ),
        )
        assertFalse(
            GenshinPackages.shouldAttemptAutoLaunch(
                enabled = true,
                genshinInForeground = false,
                alreadyAttempted = true,
                allowed = true,
            ),
        )
    }
}
