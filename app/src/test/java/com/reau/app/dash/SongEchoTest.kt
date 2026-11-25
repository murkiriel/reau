package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name of the song a tap changed to, for the dash's title (P32 Joy): the window after the tap, the song that was playing at it, the cut at the title's
 * length, one name for one tap, and the end of the wait. The clock is the test's own.
 */
class SongEchoTest {
    private var now = 100_000L
    private val echo = SongEcho(nowMs = { now })

    @Test
    fun `the window is four seconds after the tap and the title is three seconds long`() {
        assertEquals(4_000L, ActionEcho.SONG_WAIT_MS)
        assertEquals(3_000L, ActionEcho.SONG_SHOW_MS)
    }

    @Test
    fun `no tap, no name, a song that comes by itself never takes the title`() {
        assertNull(echo.onSong("Blue Monday"))
        assertFalse(echo.waiting())
    }

    @Test
    fun `the new song that comes within the window is the title`() {
        echo.asked("Old Song")
        assertTrue(echo.waiting())
        now += 800
        assertEquals("Blue Monday", echo.onSong("Blue Monday"))
    }

    @Test
    fun `the song that was playing at the tap is not the new one, and the wait goes on for it`() {
        echo.asked("Old Song")
        now += 300
        assertNull("Android Auto repeats the song it had", echo.onSong("Old Song"))
        assertTrue(echo.waiting())
        now += 500
        assertEquals("New Song", echo.onSong("New Song"))
    }

    @Test
    fun `a tap with no song playing takes the first that comes`() {
        echo.asked(null)
        assertEquals("First", echo.onSong("First"))
    }

    @Test
    fun `one name for one tap, the next metadata, a state change say, does not take the title again`() {
        echo.asked("Old Song")
        assertEquals("New Song", echo.onSong("New Song"))
        assertFalse(echo.waiting())
        assertNull(echo.onSong("New Song"))
        assertNull(echo.onSong("Another"))
    }

    @Test
    fun `just inside the window it counts, at its end it does not`() {
        echo.asked("Old Song")
        now += 3_999
        assertEquals("New Song", echo.onSong("New Song"))
        echo.asked("New Song")
        now += 4_000
        assertNull("at 4000 ms the window is over", echo.onSong("Third"))
        assertFalse(echo.waiting())
        now += 1
        assertNull("and it stays over", echo.onSong("Third"))
    }

    @Test
    fun `a song that comes after the window is not taken, even if a later one comes soon after`() {
        echo.asked("Old Song")
        now += 5_000
        assertNull(echo.onSong("Late Song"))
        now += 100
        assertNull(echo.onSong("Later Song"))
    }

    @Test
    fun `a second tap opens a new window from itself, told from the song it found`() {
        echo.asked("A")
        now += 3_000
        echo.asked("B")
        now += 3_500
        assertEquals("the second tap's window is still open, 6500 ms after the first", "C", echo.onSong("C"))
    }

    @Test
    fun `a second tap's song is told from the one at that tap`() {
        echo.asked("A")
        assertEquals("B", echo.onSong("B"))
        echo.asked("B")
        assertNull("B again is not the new one", echo.onSong("B"))
        assertEquals("C", echo.onSong("C"))
    }

    @Test
    fun `how much of the wait is left, for the title to hold the action's name`() {
        assertEquals(0L, echo.remainingMs())
        echo.asked("A")
        assertEquals(4_000L, echo.remainingMs())
        now += 1_500
        assertEquals(2_500L, echo.remainingMs())
        now += 2_500
        assertEquals("never negative", 0L, echo.remainingMs())
        echo.asked("A")
        assertEquals("B", echo.onSong("B"))
        assertEquals("a name that came ends the wait", 0L, echo.remainingMs())
    }

    @Test
    fun `cancel forgets the wait`() {
        echo.asked("A")
        echo.cancel()
        assertNull(echo.onSong("B"))
    }

    // ── the title's words ──

    @Test
    fun `the name is cut at what the title holds`() {
        val long = "A Really Quite Long Song Title"
        assertEquals(19, ActionEcho.LABEL_MAX)
        assertEquals("A Really Quite Long", SongEcho.titleOf(long))
        assertEquals("exactly 19 stays whole", "1234567890123456789", SongEcho.titleOf("1234567890123456789"))
        assertEquals("20 loses the last", "1234567890123456789", SongEcho.titleOf("12345678901234567890"))
    }

    @Test
    fun `the cut does not leave a space at its end, and the ends are trimmed`() {
        assertEquals("Hello", SongEcho.titleOf("  Hello  "))
        assertEquals("123456789012345678", SongEcho.titleOf("123456789012345678 9999"))
    }

    @Test
    fun `accents stay and the typographic marks turn into what the dash draws`() {
        assertEquals("Coração", SongEcho.titleOf("Coração"))
        assertEquals("Don't Stop", SongEcho.titleOf("Don’t Stop"))
    }

    @Test
    fun `a name the dash cannot draw is no title, and the wait goes on`() {
        assertNull(SongEcho.titleOf(null))
        assertNull(SongEcho.titleOf(""))
        assertNull(SongEcho.titleOf("   "))
        assertNull("only letters Latin-1 lacks would be question marks", SongEcho.titleOf("東京ドリーム"))
        echo.asked("Old")
        assertNull(echo.onSong("東京"))
        assertTrue(echo.waiting())
        assertEquals("a real name after it still counts", "New", echo.onSong("New"))
    }

    @Test
    fun `a name with some letters of another script keeps the ones the dash can read`() {
        assertEquals("Naruto ??", SongEcho.titleOf("Naruto 東京"))
    }
}
