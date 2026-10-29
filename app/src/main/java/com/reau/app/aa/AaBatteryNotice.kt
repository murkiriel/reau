package com.reau.app.aa

/**
 * The words for Android Auto's battery message (type 23, `MESSAGE_BATTERY_STATUS_NOTIFICATION`): it sends it when the **phone's** battery is low (15 % and 5 % in the
 * logs of 20/10/2026). It used to fall into the "unsupported control message" branch and was written as an `E:`; it is news, not an error. Only
 * the level and the state, no payload. Pure, so the JVM tests cover it.
 */
internal object AaBatteryNotice {
    /** `battery status from Android Auto: 15 % (3600 s left, critical)`, with only what the message carried. */
    fun line(level: Int?, remainingS: Int?, critical: Boolean?): String {
        val parts = buildList {
            level?.let { add("$it %") }
            remainingS?.let { add("$it s left") }
            if (critical == true) add("critical")
        }
        return "battery status from Android Auto" + if (parts.isEmpty()) "" else ": " + parts.joinToString(", ")
    }
}
