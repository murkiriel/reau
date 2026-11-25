package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workflow only reads (25/11/2026): the attestation of the debug-signed APK the CI builds, which is never published, went away; the one that
 * counts is the Release's, made by GitHub itself with immutable releases on. Every action stays pinned by the full commit, no job asks to write,
 * and the workflow publishes nothing. The site says nothing about the attestation (the Releases are signed on the maintainer's computer, so it would not cover them): the install page checks the APK by the fingerprint of the signature, in the three languages.
 */
class ApkAttestationTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }
    private val workflow = File(root, ".github/workflows/ci.yml").readText()

    /** The lines of the job [name] (from its header to the next job). */
    private fun job(name: String): String {
        val lines = workflow.lines()
        val start = lines.indexOf("  $name:")
        assertTrue("job $name", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { Regex("""^  [a-z][a-z-]*:\s*$""").matches(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    @Test
    fun `every action of the workflow is pinned by a full commit`() {
        val uses = Regex("""^\s*(?:-\s*)?uses:\s*(\S+)""", RegexOption.MULTILINE).findAll(workflow).map { it.groupValues[1] }.toList()
        assertTrue("the workflow uses actions: $uses", uses.isNotEmpty())
        for (action in uses) assertTrue("$action is not pinned by a 40-digit commit", Regex("""[\w./-]+@[0-9a-f]{40}""").matches(action))
    }

    @Test
    fun `no job asks to write, the APK audit included, and the debug APK is not attested`() {
        assertFalse("no id-token", Regex("""id-token:\s*write""").containsMatchIn(workflow))
        assertFalse("no attestations", Regex("""attestations:\s*write""").containsMatchIn(workflow))
        assertFalse("no attestation action", "attest-build-provenance" in workflow)
        val audit = job("auditoria-apk")
        assertTrue(audit, Regex("""permissions:\s*\n\s+contents:\s*read\s*\n""").containsMatchIn(audit))
        val top = workflow.substringBefore("\njobs:")
        assertTrue("the top permissions only read: $top", Regex("""(?m)^permissions:\s*\n  contents: read\s*\n""").containsMatchIn(top))
        assertFalse("no write at the top", Regex("""(?m)^permissions:\s*\n(  .*\n)*?  \S+: write""").containsMatchIn(top))
        assertFalse("no other write permission", Regex("""(contents|packages|actions|pull-requests|issues|deployments): write""").containsMatchIn(workflow))
    }

    @Test
    fun `the workflow still publishes nothing`() {
        for (forbidden in listOf("gh release", "action-gh-release", "create-release", "gh-pages", "upload-release-asset", "push-to-registry: true")) {
            assertFalse(forbidden, forbidden in workflow)
        }
    }

    @Test
    fun `the install page checks the APK by the signature's fingerprint in the three languages, and promises nothing about the attestation`() {
        val official = "63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970"
        for (language in listOf("pt", "en", "es")) {
            val page = File(root, "docs/$language/install.html").readText()
            val section = page.substringAfter("id=\"conferir-apk\"").substringBefore("id=\"migracao\"")
            assertTrue("$language: the section", "id=\"conferir-apk\"" in page && section.isNotBlank())
            assertTrue("$language: the whole fingerprint of the project's key", "<code>$official</code>" in section)
            assertTrue("$language: the command for the computer", "apksigner verify --print-certs" in section)
            // P32A: the app no longer shows the fingerprint on "Check for updates", so the page does not send the reader there.
            assertFalse("$language: the app does not show it any more", Regex("In the app, at the end|No app, no fim|En la app, al final").containsMatchIn(section))
            assertFalse("$language: nothing about the attestation on the site", Regex("attestation|atestação|atestación").containsMatchIn(page))
            assertFalse("$language: no gh attestation command", "gh attestation" in page)
        }
    }
}
