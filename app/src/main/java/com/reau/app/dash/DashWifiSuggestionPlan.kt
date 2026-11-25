package com.reau.app.dash

/**
 * What identifies a Wi-Fi suggestion for the dash, in plain types (no WifiManager): the name and the password, where a blank
 * password is the same as none (an open network), exactly as [DashWifiSuggestions.suggestionFor] builds it.
 *
 * Why not WifiNetworkSuggestion.equals: the documentation does not say what it compares, and the framework treats suggestions
 * with the same name and security type as one, so a changed password would look "equal" to the old one. The name and password
 * come back from the framework through getSsid and getPassphrase (API 30+), which are documented.
 */
internal data class SuggestionKey(val ssid: String, val password: String) {
    /** The framework's identity of a suggestion: the name and the security type (here open or WPA2). The password is not part. */
    fun sameIdentity(other: SuggestionKey): Boolean = ssid == other.ssid && password.isEmpty() == other.password.isEmpty()

    companion object {
        fun of(ssid: String, password: String?): SuggestionKey = SuggestionKey(ssid, if (password.isNullOrBlank()) "" else password)
    }
}

/** What to do with the suggestions this app has registered. */
internal enum class SuggestionMove {
    /** Nothing wanted (switch off, or no valid suggestion): remove every suggestion, so Android stops joining by itself. */
    CLEAR_ALL,

    /** The wanted suggestion is already registered, with the same password: do not touch it (removing it would drop the connection). */
    KEEP,

    /** Add the wanted suggestion (API 30+ replaces one of the same identity in place) and then remove the leftovers. */
    ADD,

    /** What is registered cannot be read (API 29, or the read failed): remove every suggestion and add the wanted one. */
    RESET,
}

/** [move], and the registered suggestions to remove one by one ([remove]; always empty for [SuggestionMove.CLEAR_ALL] and [SuggestionMove.RESET]). */
internal data class SuggestionPlan(val move: SuggestionMove, val remove: List<SuggestionKey> = emptyList()) {
    companion object {
        /** First API level where WifiManager.getNetworkSuggestions, WifiNetworkSuggestion.getSsid and getPassphrase exist. */
        const val READABLE_FROM_SDK = 30

        /**
         * Pure decision. [desired] is the suggestion that should be in effect (null when none); [current] what the app has
         * registered now (null when unknown); [sdkInt] the device's API level.
         *
         * Never removes a registered suggestion of the same identity as [desired]: with API 30+ the add updates it in place, and
         * removing by the framework's identity would take the updated one away too.
         */
        fun of(desired: SuggestionKey?, current: List<SuggestionKey>?, sdkInt: Int): SuggestionPlan {
            if (desired == null) return SuggestionPlan(SuggestionMove.CLEAR_ALL)
            if (sdkInt < READABLE_FROM_SDK || current == null) return SuggestionPlan(SuggestionMove.RESET)
            val leftovers = current.filter { !it.sameIdentity(desired) }
            return if (desired in current) SuggestionPlan(SuggestionMove.KEEP, leftovers) else SuggestionPlan(SuggestionMove.ADD, leftovers)
        }
    }
}
