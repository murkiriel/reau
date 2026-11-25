package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the phone-Wi-Fi-off notice and the voice are fed from and what audio they ask for. These are of the text of the source (the places need a
 * Context, a TextToSpeech or an AudioManager, as in the other "...IsWired" tests); what each piece decides is held by the behaviour tests beside them
 * (ProjectionMachineWifiOffTest, WifiDashLinkUpRadioTest, RiderVoiceRulesTest, RiderVoiceTest).
 */
class WifiOffAndVoiceAreWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `the broadcast of the radio reaches the projection, and the link looks at the radio before it asks`() {
        val events = source("service/SystemEvents.kt")
        val branch = events.substringAfter("WifiManager.WIFI_STATE_CHANGED_ACTION -> {").substringBefore("WifiManager.SUPPLICANT_STATE_CHANGED_ACTION ->")
        assertTrue(branch, "WifiRadio.heard(WifiRadio.of(radio))" in branch)
        val live = source("service/LiveSides.kt")
        assertTrue("WifiRadio.listener = radioListener" in live && "up.onWifiRadio(radioListener)" in live)
        assertTrue("radioOn = { wifi.isWifiEnabled() }" in live)
        assertTrue("the listener is given back only if it is this instance's", "\"wifi radio\" to { WifiRadio.release(radioListener) }" in live)
        assertTrue("{ on -> report(ProjectionEvent.WifiRadio(on)) }" in live)
    }

    @Test
    fun `the manager asks for no network of its own while the radio is off`() {
        val wifi = source("dash/DashWifiManager.kt")
        val request = wifi.substringAfter("private fun requestNetwork()").substringBefore("private fun radioOffNoRequest")
        assertTrue(request, "if (wifi.isWifiEnabled) askForTheNetwork() else radioOffNoRequest()" in request)
        assertTrue("the reconnection is not under way any more", "everConnected = false" in wifi.substringAfter("private fun radioOffNoRequest()").substringBefore("}"))
    }

    @Test
    fun `the service tells the rider at each change of state, and lets the voice and the notice go with the projection`() {
        val service = source("service/ProjectionService.kt")
        assertTrue("tellTheRider(lastReported, state)" in service)
        val tell = service.substringAfter("private fun tellTheRider").substringBefore("private fun countDrop")
        assertTrue("ProjectionNotification.showWifiOff(this)" in tell && "RiderVoice.speak(VoiceCue.WIFI_OFF)" in tell)
        assertTrue("ProjectionNotification.clearWifiOff(this)" in tell)
        assertTrue("RiderVoice.speak(VoiceCue.DASH_BACK)" in tell)
        val release = service.substringAfter("override fun release()").substringBefore("locks.release()")
        assertTrue(release, "ProjectionNotification.clearWifiOff(this@ProjectionService)" in release && "voice.release()" in release)
        assertTrue("RiderVoice.install(voice)" in service && "RiderVoice.uninstall(voice)" in service)
        assertTrue("the language of the app", "locale = { resources.configuration.locales[0] }" in service)
        assertTrue("the rider's switch, read at each phrase", "enabled = { SettingsStore.open(this).load().spokenAlerts }" in service)
    }

    @Test
    fun `the hot phone and the stuck dash are spoken where their notices are shown`() {
        val service = source("service/ProjectionService.kt")
        val hot = service.substringAfter("private fun warnIfHot").substringBefore("BatteryHeatWatch.Verdict.COOLED")
        assertTrue(hot, hot.indexOf("showPhoneHot") in 0 until hot.indexOf("RiderVoice.speak(VoiceCue.PHONE_HOT)"))
        val live = source("service/LiveSides.kt")
        val stuck = live.substringAfter("wifi.onDashStuck = { stuck ->").substringBefore("wifi.onBindRefused")
        assertTrue(stuck, stuck.indexOf("showDashStuck") in 0 until stuck.indexOf("RiderVoice.speak(VoiceCue.DASH_GONE)"))
        assertTrue("only in the branch that shows the notice", stuck.indexOf("RiderVoice.speak(VoiceCue.DASH_GONE)") < stuck.indexOf("} else {"))
    }

    @Test
    fun `the notice is in the alert channel and its button is an Activity intent to the Wi-Fi panel`() {
        val notification = source("service/ProjectionNotification.kt")
        val show = notification.substringAfter("fun showWifiOff").substringBefore("fun clearWifiOff")
        assertTrue(show, "Notification.Builder(context, ALERT_CHANNEL_ID)" in show)
        assertTrue(show, "PendingIntent.getActivity(context, 3, WifiPanel.intent(), PendingIntent.FLAG_IMMUTABLE)" in show)
        assertTrue(show, "Notification.Action.Builder(null, context.getString(R.string.action_wifi_on), panel)" in show)
        assertTrue("the panel is opened as a task of its own", "FLAG_ACTIVITY_NEW_TASK" in source("service/WifiPanel.kt"))
    }

    @Test
    fun `the button on the main screen opens the panel`() {
        assertTrue("onOpenWifiPanel = { com.reau.app.service.WifiPanel.open(this) }" in source("MainActivity.kt"))
        assertTrue("CardAction(stringResource(R.string.action_wifi_on), \"turn_on_wifi\", extras.onOpenWifiPanel)" in source("ui/MainParts.kt"))
    }

    @Test
    fun `nothing in the app switches the Wi-Fi on by itself, which Android does not allow an app since version 10`() {
        val offenders = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" && "setWifiEnabled" in it.readText() }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    // ── the audio of the voice ─────────────────────────────────────────────

    @Test
    fun `the voice speaks on the navigation guidance channel and asks for a transient focus that lets the others lower their sound`() {
        val android = source("service/RiderVoiceAndroid.kt")
        assertTrue("AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE" in android)
        assertTrue("AudioAttributes.CONTENT_TYPE_SPEECH" in android)
        assertTrue("AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)" in android)
        assertTrue("the engine is given the same attributes", "e.setAudioAttributes(voiceAttributes())" in android)
        assertTrue("the focus asks with them too", ".setAudioAttributes(voiceAttributes())" in android)
        assertTrue("given back", "audio.abandonAudioFocusRequest(it)" in android)
    }

    @Test
    fun `the manifest lets the app see the speech engine`() {
        assertTrue("android.intent.action.TTS_SERVICE" in File("src/main/AndroidManifest.xml").readText())
    }
}
