package com.reau.app.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The words of a Wi-Fi state for the ride log are in fixed English ([WifiState.cause]); the screen's own text (`error`, in the rider's language) must not be what the
 * log reads (the review of 26/11/2026: only the first request's refusal had a cause, and the log still switched language in the other three states). Read from the
 * source: the manager needs a Context and the network.
 */
class DashWifiCauseIsWiredTest {
    private val manager = File("src/main/java/com/reau/app/dash/DashWifiManager.kt").readText()

    /** The text of each `WifiState(` construction of the manager, up to its closing parenthesis (the parentheses of the comments in it are in pairs). */
    private fun constructions(): List<String> = manager.split("_state.value = WifiState(").drop(1).map { rest ->
        var depth = 1
        val end = rest.indexOfFirst { c ->
            if (c == '(') depth++ else if (c == ')') depth--
            depth == 0
        }
        rest.substring(0, end)
    }

    @Test
    fun `every state whose error is a text in the rider's language has its cause in fixed English`() {
        val localized = constructions().filter { "context.getString(" in it || "refusal.message(" in it || "error  = message" in it }
        assertEquals("the states with a screen text: the mid-session loss, the first refusal, the loss, the refused bind", 4, localized.size)
        for (state in localized) assertTrue(state, Regex("""cause  = "[^"]+"""").containsMatchIn(state))
    }

    @Test
    fun `the causes are plain English, one line, and say no name, address or password`() {
        val causes = Regex("""cause\s+= "([^"]+)"""").findAll(manager).map { it.groupValues[1] }.toList()
        assertEquals(4, causes.size)
        for (cause in causes) {
            assertTrue(cause, cause.all { it in ' '..'~' })
            assertTrue(cause, !cause.contains("\$ssid") && !cause.contains("pendingSsid") && !cause.contains("password", ignoreCase = true))
        }
    }

    @Test
    fun `the link up reads the cause before the error`() {
        val report = File("src/main/java/com/reau/app/service/DashLinkUp.kt").readText()
        assertTrue(report, "state.cause ?: state.error ?: " in report)
    }
}
