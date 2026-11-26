package com.reau.app.ui

import com.reau.app.R
import com.reau.app.ui.KeepAliveGuide.Brand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * The guide of what keeps the system from closing REau in the middle of a ride (plan 34, item 5): which steps a phone gets by its maker, what the texts say in
 * each language, and that the guide itself reaches only Android's standard page of the app (no component of a maker, no new permission: the Xiaomi button's
 * components are in KeepAliveOpen alone, KeepAliveOpenTest's, and its chain ends at that same page).
 */
class KeepAliveGuideTest {
    private val src = File("src/main")
    private val folders = listOf("values", "values-pt", "values-es", "values-hi")

    private fun text(folder: String, name: String) =
        Regex("""<string name="$name">(.*?)</string>""").find(File(src, "res/$folder/strings.xml").readText())?.groupValues?.get(1)

    private val names: Map<Int, String> = R.string::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .associate { it.getInt(null) to it.name }

    private fun idName(id: Int): String = names[id] ?: error("no string with the id $id")

    // -- the maker ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `Xiaomi, Redmi and POCO phones get the Xiaomi steps, by the maker or by the brand`() {
        assertEquals(Brand.XIAOMI, KeepAliveGuide.brandOf("Xiaomi", "Xiaomi"))
        assertEquals(Brand.XIAOMI, KeepAliveGuide.brandOf("Xiaomi", "Redmi"))
        assertEquals("a POCO phone says Xiaomi as its maker and POCO as its brand", Brand.XIAOMI, KeepAliveGuide.brandOf("Xiaomi", "POCO"))
        assertEquals(Brand.XIAOMI, KeepAliveGuide.brandOf("POCO", "poco"))
        assertEquals(Brand.XIAOMI, KeepAliveGuide.brandOf("unknown", "Redmi"))
    }

    @Test
    fun `realme, OPPO and OnePlus phones get the ColorOS family steps`() {
        assertEquals(Brand.OPPO, KeepAliveGuide.brandOf("realme", "realme"))
        assertEquals(Brand.OPPO, KeepAliveGuide.brandOf("OPPO", "OPPO"))
        assertEquals(Brand.OPPO, KeepAliveGuide.brandOf("OnePlus", "OnePlus"))
        assertEquals(Brand.OPPO, KeepAliveGuide.brandOf("REALME", "Realme"))
        assertEquals(Brand.OPPO, KeepAliveGuide.brandOf("unknown", "oneplus"))
    }

    @Test
    fun `case and spaces around the name do not matter, and an empty or missing one is another maker`() {
        assertEquals(Brand.XIAOMI, KeepAliveGuide.brandOf("  XIAOMI ", null))
        assertEquals(Brand.OTHER, KeepAliveGuide.brandOf(null, null))
        assertEquals(Brand.OTHER, KeepAliveGuide.brandOf("", ""))
        assertEquals("a name only contains the word", Brand.OTHER, KeepAliveGuide.brandOf("Xiaomi Technology Co", "generic"))
    }

    @Test
    fun `any other maker gets the general steps`() {
        for (maker in listOf("samsung" to "samsung", "Google" to "google", "motorola" to "motorola", "vivo" to "vivo", "HUAWEI" to "HONOR", "TCL" to "alcatel")) {
            assertEquals(maker.toString(), Brand.OTHER, KeepAliveGuide.brandOf(maker.first, maker.second))
        }
    }

    // -- the steps ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the Xiaomi steps are the padlock, the battery line, the autostart and, last, the optional performance mode`() {
        assertEquals(
            listOf("keepalive_step_lock", "keepalive_step_xiaomi_battery", "keepalive_step_autostart", "keepalive_step_xiaomi_performance"),
            KeepAliveGuide.steps(Brand.XIAOMI).map(::idName),
        )
        assertEquals("keepalive_brand_xiaomi", idName(KeepAliveGuide.title(Brand.XIAOMI)))
    }

    @Test
    fun `the realme, OPPO and OnePlus steps are the padlock, the battery usage and the automatic start`() {
        assertEquals(
            listOf("keepalive_step_lock", "keepalive_step_oppo_battery", "keepalive_step_oppo_autostart"),
            KeepAliveGuide.steps(Brand.OPPO).map(::idName),
        )
        assertEquals("keepalive_brand_oppo", idName(KeepAliveGuide.title(Brand.OPPO)))
    }

    @Test
    fun `the general steps are the battery optimization the app already asks for, and the padlock if the phone has one`() {
        assertEquals(listOf("keepalive_step_other_battery", "keepalive_step_other_lock"), KeepAliveGuide.steps(Brand.OTHER).map(::idName))
        assertEquals("keepalive_brand_other", idName(KeepAliveGuide.title(Brand.OTHER)))
    }

    @Test
    fun `every step and title is in the four languages`() {
        val ids = Brand.entries.flatMap { KeepAliveGuide.steps(it) + KeepAliveGuide.title(it) }.distinct().map(::idName) +
            listOf("keepalive_title", "keepalive_intro", "keepalive_honest", "keepalive_open_app_info", "permissions_keep_alive", "action_keepalive_guide") +
            // The ticks and the advice of the page (26/11/2026, A3).
            Brand.entries.flatMap { KeepAliveGuide.items(it) }.map { idName(KeepAliveGuide.label(it)) } +
            listOf(true, false).map { idName(KeepAliveGuide.batteryAdvice(it)) } + listOf("keepalive_check_title", "keepalive_check_hint", "keepalive_open_autostart")
        for (folder in folders) for (name in ids) assertFalse("$folder: $name", text(folder, name).isNullOrBlank())
    }

    // -- the words ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the Portuguese guide says what was asked`() {
        fun pt(name: String) = text("values-pt", name)!!
        assertEquals("Manter o REau vivo", pt("keepalive_title"))
        assertTrue(pt("keepalive_step_lock"), pt("keepalive_step_lock").let { "apps recentes" in it && "cadeado" in it })
        assertTrue(pt("keepalive_step_xiaomi_battery").let { "Economia de bateria" in it && "Sem restrições" in it })
        assertTrue(pt("keepalive_step_autostart").contains("Inicialização automática"))
        assertTrue(pt("keepalive_step_xiaomi_performance").let { "Opcional" in it && "desempenho" in it })
        assertTrue(pt("keepalive_step_oppo_battery").let { "Uso da bateria" in it && "segundo plano" in it })
        assertTrue(pt("keepalive_step_oppo_autostart").contains("Inicialização automática"))
        assertTrue(pt("keepalive_step_other_battery").contains(pt("perm_battery_title")))
    }

    @Test
    fun `the guide says that the steps lower the chance and do not guarantee it, in each language`() {
        assertTrue(text("values", "keepalive_honest")!!.let { "lower the chance" in it && "do not guarantee" in it })
        assertTrue(text("values-pt", "keepalive_honest")!!.let { "diminuem a chance" in it && "não garantem" in it })
        assertTrue(text("values-es", "keepalive_honest")!!.let { "reducen la probabilidad" in it && "no la eliminan" in it })
        assertTrue(text("values-hi", "keepalive_honest")!!.let { "संभावना घटाते हैं" in it && "पक्का नहीं" in it })
    }

    @Test
    fun `the step that sends to the permissions page names the line as the page writes it`() {
        for (folder in folders) {
            assertTrue(folder, text(folder, "keepalive_step_other_battery")!!.contains(text(folder, "perm_battery_title")!!))
        }
    }

    @Test
    fun `no text of the guide names an app, a project or a phone model`() {
        val names = Regex("""Magisk|Shizuku|Waze|Gemini|Android Auto|MIUI Optimization|Mi Band|Galaxy|Pixel""", RegexOption.IGNORE_CASE)
        for (folder in folders) {
            for (name in KeepAliveGuide.steps(Brand.XIAOMI) + KeepAliveGuide.steps(Brand.OPPO) + KeepAliveGuide.steps(Brand.OTHER)) {
                val body = text(folder, idName(name))!!
                assertFalse("$folder/${idName(name)}: $body", names.containsMatchIn(body))
            }
        }
    }

    // -- how it reaches the phone ---------------------------------------------------------------------------------------------------

    @Test
    fun `the guide opens only Android's standard page of the app, and no component of a maker`() {
        val code = listOf("ui/KeepAliveGuide.kt", "ui/KeepAliveScreen.kt", "data/SystemClosedRide.kt").joinToString("\n") { File(src, "java/com/reau/app/$it").readText() }
        for (needle in listOf("ComponentName", "com.miui", "com.coloros", "com.oplus", "com.oppo", "com.android.settings.", "startActivity", "Intent(")) {
            assertFalse(needle, needle in code)
        }
        val activity = File(src, "java/com/reau/app/MainActivity.kt").readText()
        val opener = activity.substringAfter("fun openAppSettings(").substringBefore("\n    }\n")
        assertTrue(opener, "Settings.ACTION_APPLICATION_DETAILS_SETTINGS" in opener && "runCatching" in opener)
        val manifest = File(src, "AndroidManifest.xml").readText()
        assertFalse("nothing of a maker in the manifest's <queries>", Regex("miui|xiaomi|coloros|oplus|oppo|heytap|realme|oneplus", RegexOption.IGNORE_CASE).containsMatchIn(manifest))
    }

    @Test
    fun `the guide's screen has the steps of the phone's family and the button to the app's details`() {
        val screen = File(src, "java/com/reau/app/ui/KeepAliveScreen.kt").readText()
        assertTrue(screen.contains("CardAction(stringResource(R.string.keepalive_open_app_info), \"keepalive_open_app_info\", onOpenAppInfo)"))
        assertTrue(screen.contains("KeepAliveGuide.steps(brand).forEachIndexed { i, step -> Step(i + 1, stringResource(step)) }"))
        assertTrue(screen.contains("brand: KeepAliveGuide.Brand = KeepAliveGuide.current()"))
        assertTrue(File(src, "java/com/reau/app/ui/KeepAliveGuide.kt").readText().contains("brandOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND)"))
    }
}
