package com.reau.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "waiting for Android Auto" notice on the dash: what decides it is [com.reau.app.aa.WaitingForAndroidAuto] (WaitingForAndroidAutoTest) and the badge
 * rule of the compositor (BadgeLeavesWithPictureTest). Where it is fed from needs the live sides (a Context, the dash session, the compositor's GL
 * thread), so those lines are read from the source, as the other "...IsWired" tests do.
 */
class WaitingNoticeIsWiredTest {
    private val main = File("src/main/java/com/reau/app")
    private val live = File(main, "service/LiveSides.kt").readText()
    private val compositor = File(main, "video/DashCompositor.kt").readText()

    private fun watch() = live.substringAfter("private suspend fun kotlinx.coroutines.CoroutineScope.watchWaiting()").substringBefore("override fun release()")

    @Test
    fun `the loop is started with the app video watch, only when there is a compositor and a receiver`() {
        assertTrue(live, "if (started && receiver != null) {\n            scope.launch { watchAppVideo() }\n            scope.launch { watchWaiting() }" in live)
    }

    @Test
    fun `it is fed Android Auto's link being ACTIVE, the dash being READY as the machine reads it, and the pictures received`() {
        val loop = watch()
        assertTrue(loop, "ProjectionMachine.linkOf(session.state.value) == DashLink.READY" in loop)
        assertTrue(loop, "receiver?.state == AaLinkState.ACTIVE" in loop)
        assertTrue(loop, "com.reau.app.aa.AaVideoMeter.units.get()" in loop)
        assertTrue(loop, "android.os.SystemClock.elapsedRealtime()" in loop)
    }

    @Test
    fun `the change goes to the log and to the badge`() {
        val loop = watch()
        assertTrue(loop, "the dash says it is waiting for Android Auto" in loop)
        assertTrue(loop, "badgeWaiting = change\n            showBadge()" in loop)
    }

    @Test
    fun `the waiting text wins over the stuck-picture text and leaves with the next picture`() {
        val show = live.substringAfter("private fun showBadge()").substringBefore("// The distances to the dash")
        assertTrue(show, show.indexOf("badgeWaiting -> appContext.getString(R.string.waiting_badge)") in 0 until show.indexOf("badgeStale -> appContext.getString(R.string.stale_badge)"))
        assertTrue(show, "compositor.setStaleBadge(text, leavesWithPicture = badgeWaiting)" in show)
    }

    @Test
    fun `the stuck-picture check goes through the same badge`() {
        assertTrue("badgeStale = isStale\n                showBadge()" in live)
    }

    @Test
    fun `the compositor drops the notice when a new picture is latched, before it draws it`() {
        val latch = compositor.substringAfter("val latched = gl.latch()").substringBefore("waitClock.arrived(")
        assertTrue(latch, "val kept = CompositorRules.badgeAfterPicture(staleBadge, badgeLeavesWithPicture)\n                        if (kept != staleBadge) setStaleBadge(kept)" in latch)
    }

    @Test
    fun `the text is in the three languages as the owner worded it`() {
        val res = File("src/main/res")
        assertTrue("""<string name="waiting_badge">Waiting for Android Auto</string>""" in File(res, "values/strings.xml").readText())
        assertTrue("""<string name="waiting_badge">Aguardando o Android Auto</string>""" in File(res, "values-pt/strings.xml").readText())
        assertTrue("""<string name="waiting_badge">Esperando Android Auto</string>""" in File(res, "values-es/strings.xml").readText())
    }

    @Test
    fun `the loop looks four times a second`() {
        assertEquals(1, Regex("""private const val WAITING_POLL_MS = 250L""").findAll(live).count())
    }
}
