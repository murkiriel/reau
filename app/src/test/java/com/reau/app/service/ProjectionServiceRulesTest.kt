package com.reau.app.service

import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionService.Companion.TapNote
import com.reau.app.settings.REauSettings
import com.reau.app.weather.RingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the projection service decides on each command, with no Android service (the service itself is ProjectionEndToEndTest's, on a device). */
class ProjectionServiceRulesTest {
    @Test
    fun `Ligar and Desligar tell the server's starter at once, and no other command does`() {
        assertEquals(true, ProjectionService.riderSwitch(ProjectionService.ACTION_START))
        assertEquals(false, ProjectionService.riderSwitch(ProjectionService.ACTION_STOP))
        assertNull(ProjectionService.riderSwitch(ProjectionService.ACTION_START_CARD))
        assertNull(ProjectionService.riderSwitch(ProjectionService.ACTION_MARK))
        assertNull(ProjectionService.riderSwitch(ProjectionService.ACTION_REFIT))
        assertNull(ProjectionService.riderSwitch(null))
    }

    @Test
    fun `a Desligar on a projection that is on begins a stop, and a Ligar after it, before it ends, waits behind it`() {
        assertEquals(TapNote.STOP_TAPPED, ProjectionService.tapNote(ProjectionService.ACTION_STOP, Phase.RUNNING, 0L))
        assertEquals(TapNote.STOP_TAPPED, ProjectionService.tapNote(ProjectionService.ACTION_STOP, Phase.PREPARING, 0L))
        assertEquals("nothing on: nothing to stop", TapNote.NONE, ProjectionService.tapNote(ProjectionService.ACTION_STOP, Phase.STOPPED, 0L))
        assertEquals(TapNote.LIGAR_DURING_STOP, ProjectionService.tapNote(ProjectionService.ACTION_START, Phase.RUNNING, 12_345L))
        assertEquals(TapNote.LIGAR_DURING_STOP, ProjectionService.tapNote(ProjectionService.ACTION_START, Phase.STOPPED, 12_345L))
        assertEquals("a Ligar with no stop going on", TapNote.NONE, ProjectionService.tapNote(ProjectionService.ACTION_START, Phase.STOPPED, 0L))
        assertEquals(TapNote.NONE, ProjectionService.tapNote(ProjectionService.ACTION_REFIT, Phase.RUNNING, 12_345L))
        assertEquals(TapNote.NONE, ProjectionService.tapNote(null, Phase.RUNNING, 12_345L))
    }

    @Test
    fun `Gemini asks for the microphone type only with the switch on and the permission to record`() {
        val on = REauSettings(gemini = true)
        assertTrue(ProjectionService.foregroundNeeds(on, recordAudioGranted = true, fineLocationGranted = false).microphone)
        assertFalse("no permission", ProjectionService.foregroundNeeds(on, recordAudioGranted = false, fineLocationGranted = true).microphone)
        assertFalse("switch off", ProjectionService.foregroundNeeds(REauSettings(gemini = false), true, true).microphone)
    }

    @Test
    fun `the info ring's GPS asks for the location type only with the ring, the location switch, the GPS card and the permission`() {
        val all = REauSettings(infoRing = true, useLocation = true, infoRingData = RingData.ALL)
        assertTrue(ProjectionService.foregroundNeeds(all, recordAudioGranted = false, fineLocationGranted = true).location)
        assertFalse("no permission", ProjectionService.foregroundNeeds(all, true, fineLocationGranted = false).location)
        assertFalse("ring off", ProjectionService.foregroundNeeds(all.copy(infoRing = false), true, true).location)
        assertFalse("'Usar a localização' off", ProjectionService.foregroundNeeds(all.copy(useLocation = false), true, true).location)
        assertFalse("GPS card unticked", ProjectionService.foregroundNeeds(all.copy(infoRingData = RingData.ALL and RingData.GPS.bit.inv()), true, true).location)
    }

    @Test
    fun `the two needs are independent`() {
        val both = REauSettings(gemini = true, infoRing = true, useLocation = true, infoRingData = RingData.ALL)
        val needs = ProjectionService.foregroundNeeds(both, recordAudioGranted = true, fineLocationGranted = true)
        assertTrue(needs.microphone && needs.location)
        val none = ProjectionService.foregroundNeeds(REauSettings(gemini = false, infoRing = false), true, true)
        assertFalse(none.microphone || none.location)
    }

    @Test
    fun `a start builds its sides only when the projection is stopped and the command says which kind`() {
        assertTrue(ProjectionService.buildsSides(sidesFor = false, phase = Phase.STOPPED))
        assertTrue(ProjectionService.buildsSides(sidesFor = true, phase = Phase.STOPPED))
        assertFalse("a start on a running one keeps its sides", ProjectionService.buildsSides(false, Phase.RUNNING))
        assertFalse(ProjectionService.buildsSides(false, Phase.PREPARING))
        assertFalse("a stop brings no kind of sides", ProjectionService.buildsSides(null, Phase.STOPPED))
    }

    @Test
    fun `a mark needs a projection that is on and a refit one that is running`() {
        assertTrue(ProjectionService.markApplies(Phase.RUNNING))
        assertTrue(ProjectionService.markApplies(Phase.PREPARING))
        assertFalse(ProjectionService.markApplies(Phase.STOPPED))
        assertTrue(ProjectionService.refitApplies(Phase.RUNNING))
        assertFalse("not while preparing", ProjectionService.refitApplies(Phase.PREPARING))
        assertFalse(ProjectionService.refitApplies(Phase.STOPPED))
    }

    @Test
    fun `only a drop of Android Auto's link counts for the summary`() {
        assertTrue(ProjectionService.isAaDrop(ProjectionEvent.AndroidAutoChanged(AaLinkState.DROPPED)))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE)))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.AndroidAutoChanged(AaLinkState.UNRESPONSIVE)))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.Start))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.Stop))
    }
}
