package com.reau.app.service

import com.reau.app.aa.AaCaller
import com.reau.app.dash.CallCards
import com.reau.app.dash.CallNotice
import com.reau.app.dash.CallPhase
import com.reau.app.dash.CallWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the call card is given to draw ([CallCardFeed]) when Android Auto tells the number of a call before the name of its contact (a call the rider makes): the name field stays
 * empty while the name is waited for, the name is written when it comes (half a second later, say), and the number only when it never comes in 2 s. The clock is the test's own
 * (the tracker's); the timer that draws again at the end of the wait is a coroutine that the test never lets run (the scope is cancelled at the end), so the test calls
 * the feed at the moments it wants, as the tick would.
 */
class CallCardFeedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 10_000L
    private val shown = mutableListOf<CallNotice?>()
    private val logs = mutableListOf<String>()
    private val words = CallWords("Answer", "Decline", { "On call $it" }, "End", "Calling", { "Ended $it" }, "Missed")
    private val feed = CallCardFeed(scope, { shown += it }, { words }, { logs += it }, CallCards.Tracker { now }).also { it.switch(true) }

    @After
    fun tearDown() = scope.cancel()

    private fun lastName() = shown.last()!!.card.name

    @Test
    fun `the number comes first and the name half a second later, and only the name is ever written`() {
        feed.heard(AaCaller("5511999990000", inCall = true, numberOnly = true), "Private")
        assertEquals("", lastName())
        now += 500
        feed.heard(AaCaller("Ana", inCall = true), "Private")
        assertEquals("Ana", lastName())
        now += 5_000
        feed.heard(AaCaller("Ana", inCall = true), "Private")
        assertEquals("Ana", lastName())
        assertEquals("the number was not drawn at any moment", emptyList<String>(), shown.filterNotNull().map { it.card.name }.filter { "55" in it })
    }

    @Test
    fun `a name that never comes is the number once the 2 s are over, at the next drawing`() {
        feed.heard(AaCaller("5511999990000", numberOnly = true), "Private")
        assertEquals("", lastName())
        now += CallCards.NAME_WAIT_MS
        feed.heard(AaCaller("5511999990000", numberOnly = true), "Private")
        assertEquals("5511999990000", lastName())
    }

    @Test
    fun `a call with no name and no number says the private word at once`() {
        feed.heard(AaCaller(""), "Private")
        assertEquals("Private", lastName())
    }

    @Test
    fun `a ringing call that ends unanswered is the missed card, with the name it had`() {
        feed.heard(AaCaller("Ana"), "Private")
        feed.heard(null, "Private")
        assertEquals(CallPhase.MISSED, shown.last()!!.card.phase)
        assertEquals("Ana", lastName())
    }
}
