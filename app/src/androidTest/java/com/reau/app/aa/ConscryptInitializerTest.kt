package com.reau.app.aa

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.Security
import javax.net.ssl.SSLContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Conscrypt was put first in the process's list of security providers, so the update check and the weather, which have
 * nothing to do with Android Auto, also used the bundled copy. It is now at the end: Android Auto's handshake asks for it by
 * name, everything else keeps the platform's.
 */
@RunWith(AndroidJUnit4::class)
class ConscryptInitializerTest {
    @Test
    fun conscryptIsAvailableByNameButIsNotTheProcessDefault() {
        assertTrue("Conscrypt installed", ConscryptInitializer.initialize())
        assertNotNull(Security.getProvider("Conscrypt"))
        assertNotEquals("not the first provider", "Conscrypt", Security.getProviders().first().name)
        assertEquals("Android Auto's handshake gets it by name", "Conscrypt", SSLContext.getInstance("TLS", "Conscrypt").provider.name)
        assertNotEquals("everything else keeps the platform's", "Conscrypt", SSLContext.getDefault().provider.name)
    }
}
