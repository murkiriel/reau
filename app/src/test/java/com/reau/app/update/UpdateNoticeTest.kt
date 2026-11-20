package com.reau.app.update

import com.reau.app.settings.REauSettings
import com.reau.app.update.UpdateNotice.Decision
import com.reau.app.update.UpdateNotice.Plan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily notice of a newer version (P32A): when to tell (a newer version, not told before, the switch on), when not (told already, up to date, a development
 * build of the published version, names that cannot be compared, no answer, switch off), the job's scheduling and cancelling by the switch, the log lines, and the wiring
 * of the job, the channel, the setting and the texts. The decisions are pure; the JobScheduler and the notification are Android's, so their wiring is read from the source.
 */
class UpdateNoticeTest {
    private fun latest(tag: String) = ReleaseCheck.Result.Latest(tag, "https://github.com/Murkiriel/reau/releases/tag/$tag")

    // ── whether to tell ──

    @Test
    fun `a newer version that was not told yet is told`() {
        assertEquals(Decision.NOTIFY, UpdateNotice.decide(true, "0.3.0", latest("v0.3.1"), lastTold = null))
        assertEquals("a version told before, then a still newer one", Decision.NOTIFY, UpdateNotice.decide(true, "0.3.0", latest("v0.4.0"), lastTold = "v0.3.1"))
    }

    @Test
    fun `the same version is told only once`() {
        assertEquals(Decision.ALREADY_TOLD, UpdateNotice.decide(true, "0.3.0", latest("v0.3.1"), lastTold = "v0.3.1"))
    }

    @Test
    fun `a build that is the published version or later is up to date, and nothing is told`() {
        assertEquals(Decision.UP_TO_DATE, UpdateNotice.decide(true, "0.3.0", latest("v0.3.0"), null))
        assertEquals(Decision.UP_TO_DATE, UpdateNotice.decide(true, "0.4.0", latest("v0.3.0"), null))
    }

    @Test
    fun `a development build of the published version is not told anything, which would be false`() {
        assertEquals(Decision.DEV_BUILD, UpdateNotice.decide(true, "0.3.0-dev+26.11.20-10.00", latest("v0.3.0"), null))
        assertEquals(Decision.DEV_BUILD, UpdateNotice.decide(true, "0.3.0-dev+26.11.20-10.00", latest("v0.3.0"), "v0.3.0"))
    }

    @Test
    fun `a development build is told about a version really newer in its numbers, as the button says`() {
        assertEquals(Decision.NOTIFY, UpdateNotice.decide(true, "0.3.0-dev+26.11.20-10.00", latest("v0.4.0"), null))
    }

    @Test
    fun `names that cannot be compared tell nothing`() {
        assertEquals(Decision.UNKNOWN, UpdateNotice.decide(true, "weird", latest("v0.3.1"), null))
        assertEquals(Decision.UNKNOWN, UpdateNotice.decide(true, "0.3.0", ReleaseCheck.Result.Latest("nightly", "https://github.com/Murkiriel/reau/releases"), null))
    }

    @Test
    fun `no release, an offline phone, a limit or a bad answer tell nothing`() {
        assertEquals(Decision.NO_ANSWER, UpdateNotice.decide(true, "0.3.0", ReleaseCheck.Result.NoRelease, null))
        for (why in ReleaseCheck.Why.entries) assertEquals("$why", Decision.NO_ANSWER, UpdateNotice.decide(true, "0.3.0", ReleaseCheck.Result.Failed(why), null))
    }

    @Test
    fun `with the switch off nothing is told, whatever the answer`() {
        assertEquals(Decision.OFF, UpdateNotice.decide(false, "0.3.0", latest("v9.9.9"), null))
        assertEquals(Decision.OFF, UpdateNotice.decide(false, "0.3.0", ReleaseCheck.Result.NoRelease, null))
    }

    @Test
    fun `only a newer version not told before ever notifies, over every combination`() {
        val currents = listOf("0.3.0", "0.3.0-dev+1", "0.2.9", "x")
        val tags = listOf("v0.3.0", "v0.3.1", "v1.0.0", "v0.1.0", "junk")
        for (enabled in listOf(true, false)) for (current in currents) for (tag in tags) for (told in listOf(null, tag, "v0.0.1")) {
            val decision = UpdateNotice.decide(enabled, current, latest(tag).takeIf { ReleaseCheck.coreOf(tag) != null } ?: ReleaseCheck.Result.Failed(ReleaseCheck.Why.BAD_ANSWER), told)
            if (decision == Decision.NOTIFY) {
                assertTrue("$enabled $current $tag $told", enabled && ReleaseCheck.verdict(current, tag) == ReleaseCheck.Verdict.NEWER && told != tag)
            }
        }
    }

    // ── the job ──

    @Test
    fun `the job is scheduled once when the switch is on and Android has none`() {
        assertEquals(Plan.SCHEDULE, UpdateNotice.plan(enabled = true, pending = false))
        assertEquals("not again at every start", Plan.KEEP, UpdateNotice.plan(enabled = true, pending = true))
    }

    @Test
    fun `the switch off cancels the job, and with none there is nothing to do`() {
        assertEquals(Plan.CANCEL, UpdateNotice.plan(enabled = false, pending = true))
        assertEquals(Plan.NOTHING, UpdateNotice.plan(enabled = false, pending = false))
    }

    @Test
    fun `the job is daily and has its own number and channel`() {
        assertEquals(24L * 60 * 60 * 1000, UpdateNotice.PERIOD_MS)
        assertEquals("reau_updates", UpdateNotice.CHANNEL_ID)
        assertNotEquals("apart from the alert channel", "reau_alerts", UpdateNotice.CHANNEL_ID)
        val alert = File("src/main/java/com/reau/app/service/ProjectionNotification.kt").readText()
        assertFalse("the alert channel is not the updates' one", alert.contains(UpdateNotice.CHANNEL_ID))
    }

    // ── what is said ──

    @Test
    fun `the title says the version without its v`() {
        assertEquals("0.3.1", UpdateNotice.versionLabel("v0.3.1"))
        assertEquals("0.3.1", UpdateNotice.versionLabel(" V0.3.1 "))
        assertEquals("0.3.1", UpdateNotice.versionLabel("0.3.1"))
    }

    @Test
    fun `the log line says up to date, newer, failed and the rest, with no data of the rider`() {
        assertEquals("update check (daily): up to date", UpdateNotice.logLine(Decision.UP_TO_DATE, latest("v0.3.0")))
        assertEquals("update check (daily): newer v0.3.1, told", UpdateNotice.logLine(Decision.NOTIFY, latest("v0.3.1")))
        assertEquals("update check (daily): newer v0.3.1, already told", UpdateNotice.logLine(Decision.ALREADY_TOLD, latest("v0.3.1")))
        assertEquals("update check (daily): failed (offline)", UpdateNotice.logLine(Decision.NO_ANSWER, ReleaseCheck.Result.Failed(ReleaseCheck.Why.OFFLINE)))
        assertEquals("update check (daily): failed (limited)", UpdateNotice.logLine(Decision.NO_ANSWER, ReleaseCheck.Result.Failed(ReleaseCheck.Why.LIMITED)))
        assertEquals("update check (daily): no release published", UpdateNotice.logLine(Decision.NO_ANSWER, ReleaseCheck.Result.NoRelease))
        assertTrue(UpdateNotice.logLine(Decision.DEV_BUILD, latest("v0.3.0")).startsWith("update check (daily): development build"))
        assertTrue(UpdateNotice.logLine(Decision.UNKNOWN, latest("v0.3.0")).startsWith("update check (daily): versions not comparable"))
        assertTrue(UpdateNotice.logLine(Decision.OFF, ReleaseCheck.Result.NoRelease).startsWith("update check (daily): switched off"))
    }

    // ── the setting ──

    @Test
    fun `the setting is on by default, survives the sanitizing and is saved`() {
        assertTrue(REauSettings().updateNotice)
        assertTrue(REauSettings().sanitized().updateNotice)
        assertFalse(REauSettings(updateNotice = false).sanitized().updateNotice)
        val prefs = com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs()
        assertTrue("a phone that never saved it", com.reau.app.settings.SettingsStore(prefs).load().updateNotice)
        com.reau.app.settings.SettingsStore(prefs).save(REauSettings(updateNotice = false))
        assertFalse(com.reau.app.settings.SettingsStore(prefs).load().updateNotice)
        com.reau.app.settings.SettingsStore(prefs).save(REauSettings(updateNotice = true))
        assertTrue(com.reau.app.settings.SettingsStore(prefs).load().updateNotice)
    }

    // ── the wiring, read from the source ──

    private fun text(path: String) = File("src/$path").readText()

    @Test
    fun `the manifest declares the job service with the bind permission, not exported, and no new permission`() {
        val manifest = text("main/AndroidManifest.xml")
        val service = Regex("""<service[^>]*UpdateCheckJob[^>]*/>""", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
        assertTrue("the service is declared", service != null)
        assertTrue(service, service!!.contains("""android:name=".update.UpdateCheckJob""""))
        assertTrue(service, service.contains("""android:permission="android.permission.BIND_JOB_SERVICE""""))
        assertTrue(service, service.contains("""android:exported="false""""))
        assertFalse("no boot permission was added", manifest.contains("RECEIVE_BOOT_COMPLETED"))
    }

    @Test
    fun `the job is periodic and needs a network, is not persisted, and asks the way the button does`() {
        val code = text("main/java/com/reau/app/update/UpdateNoticeAndroid.kt")
        assertTrue(code, code.contains(".setPeriodic(UpdateNotice.PERIOD_MS)"))
        assertTrue(code, code.contains(".setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)"))
        assertFalse("persisting needs RECEIVE_BOOT_COMPLETED", code.contains("setPersisted(true)"))
        assertTrue(code, code.contains("""ReleaseCheck.fetch("REau/${'$'}{BuildConfig.VERSION_NAME}")"""))
        assertTrue(code, code.contains("UpdateNotice.plan(enabled, pending = scheduler.getPendingJob(UpdateNotice.JOB_ID) != null)"))
        assertTrue("the switch off cancels", code.contains("scheduler.cancel(UpdateNotice.JOB_ID)"))
        assertTrue("one notification per version: the tag is kept only when it was posted", code.contains("post(context, result)") && code.contains("KEY_LAST_TOLD, result.tag"))
        assertTrue("a refused notification permission does not count as told", code.contains("areNotificationsEnabled()"))
    }

    @Test
    fun `the notification has its own quiet channel and opens the release page in the browser`() {
        val code = text("main/java/com/reau/app/update/UpdateNoticeAndroid.kt")
        assertTrue(code, code.contains("NotificationChannel(UpdateNotice.CHANNEL_ID"))
        assertTrue("low importance: no sound", code.contains("NotificationManager.IMPORTANCE_LOW"))
        assertFalse(code, code.contains("ALERT_CHANNEL_ID") || code.contains("IMPORTANCE_HIGH"))
        assertTrue(code, code.contains("Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl))"))
        assertTrue(code, code.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(code, code.contains("""getString(R.string.notif_update_title, UpdateNotice.versionLabel(latest.tag))"""))
        // The page is the project's own: ReleaseCheck keeps any other address out.
        assertEquals(ReleaseCheck.RELEASES_PREFIX + "releases", (ReleaseCheck.fromFields("v1.0.0", "https://evil.example/x") as ReleaseCheck.Result.Latest).pageUrl)
    }

    @Test
    fun `nothing is downloaded or installed by the job`() {
        val code = text("main/java/com/reau/app/update/UpdateNoticeAndroid.kt") + text("main/java/com/reau/app/update/UpdateNotice.kt")
        for (word in listOf("DownloadManager", "PackageInstaller", "REQUEST_INSTALL_PACKAGES", "openConnection", "FileOutputStream")) assertFalse(word, code.contains(word))
    }

    @Test
    fun `the app puts the job back at every start, and the switch follows the job at once`() {
        assertTrue(text("main/java/com/reau/app/REauApp.kt").contains("UpdateNoticeAndroid.sync(this, settings.updateNotice)"))
        val group = text("main/java/com/reau/app/ui/UpdateGroup.kt")
        assertTrue(group, group.contains("UpdateNoticeAndroid.sync(context, it)"))
        assertTrue(group, group.contains("change(settings.copy(updateNotice = it))"))
        assertTrue(group, group.contains("R.string.update_notice_title"))
    }

    // ── the screen is down to the basics ──

    @Test
    fun `the updates group keeps the title, the version line, the button and the result, and no signature, debug text or note`() {
        val group = text("main/java/com/reau/app/ui/UpdateGroup.kt")
        for (kept in listOf("R.string.update_check_title", "R.string.update_sub_idle", "R.string.update_button_check", "R.string.update_button_open", "\"update_status\"")) {
            assertTrue(kept, group.contains(kept))
        }
        for (gone in listOf("SigningBlock", "SigningCheck", "update_sign", "update_info", "update_note", "Fingerprint")) assertFalse(gone, group.contains(gone))
        assertTrue("the release button only for a newer version", group.contains("if (latest != null && verdict == ReleaseCheck.Verdict.NEWER) {"))
    }

    @Test
    fun `the old texts are gone from the three languages and the new ones are there, each its own`() {
        val folders = listOf("values", "values-pt", "values-es")
        val new = listOf("update_notice_title", "update_notice_sub", "notif_update_channel_name", "notif_update_channel_desc", "notif_update_title", "notif_update_text")
        val english = mutableMapOf<String, String>()
        for (folder in folders) {
            val xml = text("main/res/$folder/strings.xml")
            assertFalse("$folder: update_info is left", xml.contains("\"update_info\""))
            assertFalse("$folder: a signature text is left", xml.contains("\"update_sign_"))
            for (name in new) {
                val value = Regex("""<string name="$name">(.*?)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$folder/$name", !value.isNullOrBlank())
                if (folder == "values") english[name] = value!! else assertNotEquals("$folder/$name is the English text", english[name], value)
            }
        }
    }

    @Test
    fun `the words the owner asked for are those, in Portuguese`() {
        val pt = text("main/res/values-pt/strings.xml")
        fun v(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(pt)!!.groupValues[1]
        assertEquals("Avisar quando houver atualização", v("update_notice_title"))
        assertEquals("Atualizações", v("notif_update_channel_name"))
        assertEquals("REau %1\$s disponível", v("notif_update_title"))
        assertEquals("Verificar atualização", v("update_check_title"))
        assertEquals("Você tem a %1\$s.", v("update_sub_idle"))
        assertEquals("Verificar agora", v("update_button_check"))
        val en = text("main/res/values/strings.xml")
        assertTrue(en.contains("""<string name="notif_update_title">REau %1${'$'}s available</string>"""))
        assertTrue(text("main/res/values-es/strings.xml").contains("""<string name="notif_update_title">REau %1${'$'}s disponible</string>"""))
    }

    @Test
    fun `the docs no longer say that REau asks only when you tap, and say the daily check and its switch`() {
        val root = listOf(File(".."), File(".")).first { File(it, "docs").isDirectory }
        for (lang in listOf("pt", "en", "es")) {
            for (page in listOf("privacy", "faq", "index")) {
                val html = File(root, "docs/$lang/$page.html").readText()
                assertFalse("$lang/$page still says only when you tap", Regex("só pergunta ao GitHub.{0,120}quando você toca|only when you tap|solo cuando lo tocas|solo cuando tocas|solo consulta a GitHub cuando lo tocas").containsMatchIn(html))
            }
            assertTrue("$lang privacy says the daily check", Regex("api\\.github\\.com").containsMatchIn(File(root, "docs/$lang/privacy.html").readText()))
        }
        for (readme in listOf("README.md", "README.pt-br.md", "README.es.md")) {
            val text = File(root, readme).readText()
            assertFalse("$readme still says only when you tap", Regex("only when you tap|só pergunta ao GitHub quando você toca|solo consulta a GitHub cuando lo tocas").containsMatchIn(text))
        }
    }
}
