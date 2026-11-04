package com.omarpriv.smoothflow

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsProfileTest {
    @Test
    fun outputProfilesAreDoubleInputProfiles() {
        assertEquals(50f, 25f * 2f, 0.0f)
        assertEquals(60f, 30f * 2f, 0.0f)
    }
}
