// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/ConscryptInitializer.kt, commit 0abbe2a.
// Changes: package; the provider is added at the END of the list, not at position 1 (see initialize).
// Ported from headunit-revived (AGPLv3): ssl/ConscryptInitializer.kt
package com.reau.app.aa

import java.security.Security

object ConscryptInitializer {
    @Volatile private var initialized = false
    @Volatile private var conscryptAvailable = false

    @Synchronized
    @Suppress("TooGenericExceptionCaught") // any failure just means no Conscrypt
    fun initialize(): Boolean {
        if (initialized) return conscryptAvailable
        initialized = true

        try {
            val conscrypt = Class.forName("org.conscrypt.Conscrypt")
            val newProviderMethod = conscrypt.getMethod("newProvider")
            val provider = newProviderMethod.invoke(null) as java.security.Provider
            // At the end of the list: AapSslContext asks for it by name, and the two trips to the internet (the update check and the
            // weather) keep the platform's TLS. At position 1 every HTTPS connection of the process used the bundled Conscrypt.
            val result = Security.addProvider(provider)
            conscryptAvailable = result != -1 || Security.getProvider("Conscrypt") != null
            if (conscryptAvailable) {
                AaLog.i("Conscrypt installed as security provider (position: $result)")
            }
        } catch (e: ClassNotFoundException) {
            AaLog.e("Conscrypt library not found", e)
            conscryptAvailable = false
        } catch (e: Exception) {
            AaLog.e("Failed to initialize Conscrypt", e)
            conscryptAvailable = false
        }
        return conscryptAvailable
    }

    fun isAvailable(): Boolean = conscryptAvailable
    fun getProviderName(): String? = if (conscryptAvailable) "Conscrypt" else null
}
