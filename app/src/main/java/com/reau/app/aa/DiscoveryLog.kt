package com.reau.app.aa

/**
 * The log line for Android Auto's service discovery request. The request carries the name the owner gave the phone, which the shared log
 * must not: the line says only how long the name is (the log is shared with whoever helps with a problem).
 */
internal object DiscoveryLog {
    fun line(phoneName: String): String = "Service Discovery Request (the phone's name, ${phoneName.length} characters, is not logged)"
}
