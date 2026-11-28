package com.reau.app.devtools

import com.reau.app.ui.KeepAliveGuide
import com.reau.app.ui.KeepAliveGuide.Brand
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fake brand never exists in a release (26/11/2026, the owner's request): the release stub answers none and writes nothing, the main source only asks the one question and
 * writes the one line through it, nothing of the row, its tags, its texts or its log line is in the main source or in the release source set, and without a fake the guide reads
 * the phone's own maker, which is all a release has. The benchmark build uses the release code, so it is held by the same.
 */
class BrandFakeAbsenceInReleaseTest {
    private val root = File("src")
    private fun kotlin(dir: String): List<File> = File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun code(file: File) = file.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `the release stub answers none, writes nothing and holds no state`() {
        val stub = code(File(root, "release/java/com/reau/app/devtools/BrandFake.kt"))
        assertTrue(stub, "fun maker(): String? = null" in stub && "fun logWhenActive() = Unit" in stub)
        assertEquals("only the two functions", 2, Regex("""\bfun\b""").findAll(stub).count())
        assertFalse(stub, "var " in stub || "val " in stub || "StateFlow" in stub || "RideDiagnostics" in stub || "xiaomi" in stub.lowercase() || "realme" in stub.lowercase())
    }

    @Test
    fun `the main source asks the maker in one place and writes the line in two, and does nothing else with it`() {
        val uses = kotlin("main").flatMap { f -> Regex("""BrandFake\.(\w+)""").findAll(f.readText()).map { it.groupValues[1] to f.name }.toList() }
        assertEquals(
            uses.toString(),
            listOf("maker" to "KeepAliveGuide.kt", "logWhenActive" to "KeepAliveChecklist.kt", "logWhenActive" to "KeepAliveRoute.kt"),
            uses.sortedWith(compareBy({ listOf("maker", "logWhenActive").indexOf(it.first) }, { it.second })),
        )
    }

    @Test
    fun `the main source does not read the phone's maker anywhere else, so the one place of the fake is the only place`() {
        for (f in kotlin("main")) {
            val body = code(f)
            if (f.name != "KeepAliveGuide.kt") assertFalse("${f.name} reads the brand by itself", "brandOf(" in body || "brandFor(" in body)
        }
        val guide = code(File(root, "main/java/com/reau/app/ui/KeepAliveGuide.kt"))
        assertEquals("one place reads the phone's own maker", 1, Regex("""Build\.MANUFACTURER""").findAll(guide).count())
        assertTrue(guide, "brandFor(android.os.Build.MANUFACTURER, android.os.Build.BRAND, com.reau.app.devtools.BrandFake.maker())" in guide)
    }

    @Test
    fun `without a fake the guide reads the phone's own maker, which is all a release has`() {
        assertEquals(Brand.OTHER, KeepAliveGuide.brandFor("motorola", "motorola", null))
        assertEquals(Brand.XIAOMI, KeepAliveGuide.brandFor("Xiaomi", "Redmi", null))
        assertEquals(Brand.OPPO, KeepAliveGuide.brandFor("realme", "realme", null))
    }

    @Test
    fun `nothing of the fake's row, tags, choices or log line is in the main source or in the release source set`() {
        for (file in kotlin("main") + kotlin("release")) {
            val body = file.readText()
            assertFalse("${file.name} has the log line", "brand faked as" in body || "brand fake ended" in body)
            assertFalse("${file.name} has the row", "BrandFakeRows" in body || "debug_brand" in body)
            assertFalse("${file.name} picks a fake", "Choice.XIAOMI" in body || "Choice.REALME" in body || "BrandFake.choose" in body || "BrandFake.choice" in body)
        }
    }

    @Test
    fun `the strings of the row are only in the debug resources`() {
        for (folder in listOf("values", "values-pt", "values-es", "values-hi")) {
            for (f in File(root, "main/res/$folder").listFiles { x -> x.extension == "xml" }.orEmpty()) {
                assertFalse("${f.path} has debug_brand", "debug_brand" in f.readText())
            }
            assertTrue("$folder in debug", "debug_brand_title" in File(root, "debug/res/$folder/strings.xml").readText())
        }
        assertFalse(File(root, "release/res").exists())
    }

    @Test
    fun `the row is only in the debug source, and the release DebugTriggers has no row at all`() {
        assertTrue("BrandFakeRows.ChoiceRow()" in File(root, "debug/java/com/reau/app/devtools/DebugTriggers.kt").readText())
        assertFalse(File(root, "release/java/com/reau/app/devtools/DebugTriggers.kt").readText().contains("Brand"))
        assertFalse("the row's file is not in the release set", File(root, "release/java/com/reau/app/devtools/BrandFakeRows.kt").exists())
    }
}
