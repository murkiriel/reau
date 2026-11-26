package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line of REau's standby bucket and background restriction (26/11/2026), written at the first look of a projection and again only when one of them changes.
 * The reading itself (UsageStatsManager, ActivityManager) needs Android; [AppStandbyIsWiredTest] holds that it is tolerant of failure.
 */
class AppStandbyTest {
    @Test
    fun `every bucket Android names has its name in the log`() {
        val names = listOf(5, 10, 20, 30, 40, 45, 50).map { AppStandby.bucketName(it) }
        assertEquals(listOf("exempted", "active", "working_set", "frequent", "rare", "restricted", "never"), names)
    }

    @Test
    fun `a bucket the list does not know is its number and one that failed is unknown`() {
        assertEquals("bucket25", AppStandby.bucketName(25))
        assertEquals("unknown", AppStandby.bucketName(null))
    }

    @Test
    fun `the line says the bucket and the restriction`() {
        assertEquals("app standby: bucket=working_set background-restricted=no", AppStandby.line(AppStandby.Reading(20, false)))
        assertEquals("app standby: bucket=restricted background-restricted=yes", AppStandby.line(AppStandby.Reading(45, true)))
    }

    @Test
    fun `what could not be read is unknown and a question mark`() {
        assertEquals("app standby: bucket=unknown background-restricted=?", AppStandby.line(AppStandby.Reading(null, null)))
    }

    @Test
    fun `the line does not start with device, which the reader takes for a look at the phone`() {
        assertFalse(AppStandby.line(AppStandby.Reading(10, false)).startsWith("device "))
    }

    @Test
    fun `the first look writes at once and the same reading again writes nothing`() {
        val written = ArrayList<String>()
        val log = AppStandbyLog { written += it }
        log.offer(AppStandby.Reading(10, false))
        log.offer(AppStandby.Reading(10, false))
        log.offer(AppStandby.Reading(10, false))
        assertEquals(listOf("app standby: bucket=active background-restricted=no"), written)
    }

    @Test
    fun `a change of the bucket or of the restriction writes a new line`() {
        val written = ArrayList<String>()
        val log = AppStandbyLog { written += it }
        log.offer(AppStandby.Reading(10, false))
        log.offer(AppStandby.Reading(20, false))
        log.offer(AppStandby.Reading(20, true))
        assertEquals(3, written.size)
        assertTrue(written[1], written[1].endsWith("bucket=working_set background-restricted=no"))
        assertTrue(written[2], written[2].endsWith("bucket=working_set background-restricted=yes"))
    }

    @Test
    fun `a read that failed after one that worked is a change worth a line`() {
        val written = ArrayList<String>()
        val log = AppStandbyLog { written += it }
        log.offer(AppStandby.Reading(10, false))
        log.offer(AppStandby.Reading(null, null))
        assertEquals(2, written.size)
    }
}
