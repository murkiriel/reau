package com.reau.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What keeps the release build reproducible: in an incremental Kotlin compilation the Compose compiler writes other constants (a `$stable` read
 * with `sget`, one `$changed` bit more), and the classes2.dex and classes3.dex of a release built over an old build differ from those of a clean
 * build of the same commit. app/build.gradle.kts declares the sources once more, as an input that is not incremental, of the release and
 * benchmark Kotlin tasks; this test holds that block in place and keeps the debug build, rebuilt all day, out of it.
 */
class ReleaseReproducibleBuildTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun block(): String {
        val app = File(root(), "app/build.gradle.kts").readText()
        val start = app.indexOf("tasks.configureEach {")
        assertTrue("the tasks.configureEach block that forces a whole compilation of the release is missing", start >= 0)
        return app.substring(start, app.indexOf("android {", start))
    }

    @Test
    fun `the kotlin compilation of the release and of the benchmark build has the sources as a non incremental input`() {
        val block = block()
        assertTrue(block.contains("name.endsWith(\"ReleaseKotlin\")"))
        assertTrue(block.contains("name.endsWith(\"BenchmarkKotlin\")"))
        assertTrue(block.contains("inputs.files(fileTree(\"src\")"))
        assertTrue(block.contains("\"main/**/*.kt\"") && block.contains("\"main/**/*.java\""))
        assertTrue(block.contains("\"release/**/*.kt\"") && block.contains("\"release/**/*.java\""))
        assertTrue(block.contains(".withPropertyName(\"sourcesForAFullCompilation\")"))
        // @Incremental would make the input incremental again and undo the effect.
        assertFalse(block.contains("Incremental"))
    }

    @Test
    fun `the CI builds the release a second time, from scratch in another copy of the commit, and compares the two without the signature`() {
        val ci = File(root(), ".github/workflows/ci.yml").readText()
        val job = ci.substring(ci.indexOf("  auditoria-apk:"), ci.indexOf("\n  python:"))
        assertTrue("two builds of the release in the job", Regex(""":app:assembleRelease""").findAll(job).count() == 2)
        assertTrue("the second one in a copy at another path, not over the first build", job.contains("git clone --quiet --depth 1 \"file://\$GITHUB_WORKSPACE\" \"\$RUNNER_TEMP/segunda-copia\""))
        assertTrue("the second one is built the way the first is (debug-signed, as the CI has no release key)", job.substringAfter("segunda-copia\"\n").contains("-PreauDebugSigned=true :app:assembleRelease"))
        assertTrue("the comparison fails the job when the two differ", job.contains("python3 -B scripts/apk_compare.py \"\$RUNNER_TEMP/primeiro.apk\" \"\$RUNNER_TEMP/segundo.apk\""))
        assertTrue("the script and its tests are in the repository", File(root(), "scripts/apk_compare.py").isFile && File(root(), "scripts/test_apk_compare.py").isFile)
        assertTrue("and the tests run in the job", job.contains("test_apk_compare.py"))
    }

    @Test
    fun `the debug build stays incremental`() {
        assertFalse(block().contains("DebugKotlin"))
        val properties = File(root(), "gradle.properties").readText()
        assertFalse("kotlin.incremental=false would take the incremental compilation away from the debug build too", Regex("^\\s*kotlin\\.incremental\\s*=\\s*false", RegexOption.MULTILINE).containsMatchIn(properties))
    }

    @Test
    fun `the commit in the build is always 12 characters, not the length git picks for the size of the clone`() {
        // 26/11/2026 (review A3): a plain --short is 7 characters in a shallow clone and more in a full one, so the same commit gave two different APKs.
        val app = File(root(), "app/build.gradle.kts").readText()
        assertTrue(app.contains("commandLine(\"git\", \"rev-parse\", \"--short=12\", \"HEAD\")"))
        assertFalse("a --short with no length", Regex("\"--short\"").containsMatchIn(app))
        assertTrue("outside a git checkout it stays a question mark", app.contains("it.trim().ifEmpty { \"?\" }") && app.contains(".getOrElse(\"?\")"))
    }
}
