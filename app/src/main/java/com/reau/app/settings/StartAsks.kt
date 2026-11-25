package com.reau.app.settings

/**
 * What Ligar asks Android for before the projection starts; it starts whatever the answers. Android 13+ asks before the
 * projection's notification may show; and, while the dash's whole Wi-Fi name is not known (a clean install forgets it),
 * the location, which alone gives REau the Wi-Fi list (REau reads no position for it).
 */
object StartAsks {
    private const val TIRAMISU = 33
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"

    /** Asked together: Android 12+ wants the coarse one asked with the fine one. */
    val LOCATION = listOf(FINE_LOCATION, "android.permission.ACCESS_COARSE_LOCATION")

    fun permissions(sdk: Int, card: Boolean, notificationsGranted: Boolean, locationGranted: Boolean, dashNameKnown: Boolean): List<String> =
        listOfNotNull(NOTIFICATIONS.takeIf { sdk >= TIRAMISU && !notificationsGranted }) +
            (if (!card && !dashNameKnown && !locationGranted) LOCATION else emptyList())

    /**
     * After Ligar's window for [answers]: the app's page in Android opens when [AppPermissions.openAppPageAfter] says Android refused with no
     * window, and Ligar has not opened it before ([openedBefore]): a rider who refused on purpose (the location is optional) is not sent there at
     * every Ligar.
     */
    fun openAppPageAfter(answers: Map<String, Boolean>, elapsedMs: Long, openedBefore: Boolean): Boolean =
        !openedBefore && AppPermissions.openAppPageAfter(answers, elapsedMs)
}
