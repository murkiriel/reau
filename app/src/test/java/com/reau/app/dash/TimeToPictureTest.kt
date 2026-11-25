package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line of how long a connection took to put the picture on the dash (plan 32, C14): four marks, three stages, one line per connection. */
class TimeToPictureTest {
    @Test
    fun `a full connection says each stage and the total`() {
        val marks = TimeToPictureMarks()
        marks.requested(1_000)
        marks.networkUp(4_600)
        marks.authenticated(4_900)
        marks.streaming(8_000)
        assertEquals(
            "time to picture: 8.2 s (request→network 3.6 s, network→auth 0.3 s, auth→first keyframe 4.3 s)",
            marks.keyframeSent(9_200),
        )
    }

    @Test
    fun `only the first keyframe of a stream writes the line, and none before the stream`() {
        val marks = TimeToPictureMarks()
        marks.requested(1_000)
        marks.networkUp(2_000)
        marks.authenticated(3_000)
        assertNull(marks.keyframeSent(3_500))
        marks.streaming(4_000)
        assertEquals("time to picture: 4.0 s (request→network 1.0 s, network→auth 1.0 s, auth→first keyframe 2.0 s)", marks.keyframeSent(5_000))
        assertNull(marks.keyframeSent(6_000))
    }

    @Test
    fun `a network already up leaves the request out with the reason and counts from the network`() {
        val marks = TimeToPictureMarks()
        marks.networkUp(2_000)
        marks.authenticated(2_300)
        marks.streaming(5_000)
        assertEquals(
            "time to picture: 4.3 s from the network (request→network ?, network→auth 0.3 s, auth→first keyframe 4.0 s; " +
                "?: no request in this connection, the network was already up)",
            marks.keyframeSent(6_300),
        )
    }

    @Test
    fun `a session that began again on the same network knows only the last stage`() {
        val marks = TimeToPictureMarks()
        marks.authenticated(2_000)
        marks.streaming(3_000)
        assertEquals(
            "time to picture: 2.5 s from the authentication (request→network ?, network→auth ?, auth→first keyframe 2.5 s; " +
                "?: no request in this connection, the network was already up)",
            marks.keyframeSent(4_500),
        )
    }

    @Test
    fun `a request with no mark of the network says so`() {
        val marks = TimeToPictureMarks()
        marks.requested(1_000)
        marks.authenticated(3_000)
        marks.streaming(3_500)
        assertEquals(
            "time to picture: 3.5 s (request→network ?, network→auth ?, auth→first keyframe 1.5 s; ?: no mark when the network came up)",
            marks.keyframeSent(4_500),
        )
    }

    @Test
    fun `a request let go and asked again counts from the first`() {
        val marks = TimeToPictureMarks()
        marks.requested(1_000)
        marks.requested(11_000)
        marks.networkUp(12_000)
        marks.authenticated(12_500)
        marks.streaming(13_000)
        assertEquals(
            "time to picture: 12.5 s (request→network 11.0 s, network→auth 0.5 s, auth→first keyframe 1.0 s)",
            marks.keyframeSent(13_500),
        )
    }

    @Test
    fun `after the line the next connection starts over`() {
        val marks = TimeToPictureMarks()
        marks.requested(1_000)
        marks.networkUp(2_000)
        marks.authenticated(3_000)
        marks.streaming(4_000)
        marks.keyframeSent(5_000)
        marks.requested(100_000)
        marks.networkUp(101_000)
        marks.authenticated(101_500)
        marks.streaming(102_000)
        assertEquals(
            "time to picture: 3.0 s (request→network 1.0 s, network→auth 0.5 s, auth→first keyframe 1.5 s)",
            marks.keyframeSent(103_000),
        )
    }

    @Test
    fun `a stream that began and never showed a keyframe is not carried to the next session`() {
        val marks = TimeToPictureMarks()
        marks.requested(1_000)
        marks.networkUp(2_000)
        marks.authenticated(3_000)
        marks.streaming(4_000)
        marks.authenticated(10_000)
        assertNull(marks.keyframeSent(11_000))
    }

    @Test
    fun `forgetting clears an unfinished connection`() {
        val marks = TimeToPictureMarks()
        marks.requested(1_000)
        marks.networkUp(2_000)
        marks.forget()
        marks.authenticated(50_000)
        marks.streaming(51_000)
        assertEquals(
            "time to picture: 2.0 s from the authentication (request→network ?, network→auth ?, auth→first keyframe 2.0 s; " +
                "?: no request in this connection, the network was already up)",
            marks.keyframeSent(52_000),
        )
    }

    @Test
    fun `marks out of order are a question mark and say so`() {
        val marks = TimeToPictureMarks()
        marks.requested(5_000)
        marks.networkUp(2_000)
        marks.authenticated(6_000)
        marks.streaming(7_000)
        assertEquals(
            "time to picture: 4.0 s (request→network ?, network→auth 4.0 s, auth→first keyframe 3.0 s; ?: the marks came out of order)",
            marks.keyframeSent(9_000),
        )
    }
}
