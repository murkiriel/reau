package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The badge on the dash's video after a new picture of Android Auto is latched: the waiting notice goes at that moment, the stuck-picture badge is not its business. */
class BadgeLeavesWithPictureTest {
    @Test
    fun `the waiting notice is gone with the new picture`() {
        assertNull(CompositorRules.badgeAfterPicture("Waiting for Android Auto", leavesWithPicture = true))
    }

    @Test
    fun `the stuck-picture badge stays through a new picture, the stuck verdict being the picture check's`() {
        assertEquals("PICTURE STUCK", CompositorRules.badgeAfterPicture("PICTURE STUCK", leavesWithPicture = false))
    }

    @Test
    fun `no badge stays no badge`() {
        assertNull(CompositorRules.badgeAfterPicture(null, leavesWithPicture = false))
        assertNull(CompositorRules.badgeAfterPicture(null, leavesWithPicture = true))
    }
}
