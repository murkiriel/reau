package com.reau.app

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Gradle wrapper's jar is the one Gradle publishes for the version in gradle-wrapper.properties (its SHA-256 is at
 * services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256; the jar itself is in the gradle/gradle repository, at the tag
 * of the version). A jar that is not that one runs on every build of whoever clones the project. On a Gradle upgrade this test and the
 * properties change together.
 */
class WrapperJarTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }

    @Test
    fun `the wrapper jar is the official one of the Gradle version the project uses`() {
        val version = Regex("""gradle-([0-9.]+)-bin\.zip""")
            .find(File(root, "gradle/wrapper/gradle-wrapper.properties").readText())!!.groupValues[1]
        assertEquals("the version these hashes are for", "9.4.1", version)
        val sha = MessageDigest.getInstance("SHA-256").digest(File(root, "gradle/wrapper/gradle-wrapper.jar").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c", sha)
    }
}
