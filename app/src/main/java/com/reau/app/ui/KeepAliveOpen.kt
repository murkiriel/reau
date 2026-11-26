package com.reau.app.ui

/**
 * The button of the "Manter o REau vivo" page that opens the phone's own screen (26/11/2026, A3 of the Xiaomi and realme plan), as a chain of alternatives that
 * never fails in silence: the first that Android starts wins, each failure is a line in the ride log, and when all fail the caller tells the rider.
 *
 * Xiaomi, Redmi and POCO: the Autostart list of the Security app, then that app's page of REau's permissions, then Android's page of the app's details. The two
 * components of the maker are the ones the owner's note ([P34-D]) names and ones other apps have used for years; they change between versions of MIUI and HyperOS
 * and some phones refuse them, which is why the chain goes on (every `startActivity` is tried on its own, and none is asked about first, so the manifest needs no
 * `<queries>`). realme, OPPO and OnePlus: only the app's details, because no component of ColorOS is known to be reliable. Any other maker: the same details page.
 * No Android class here: the Android side turns a [Target] into an `Intent` ([KeepAliveOpenAndroid]), so the chain is tested on the JVM with a `start` that is handed in.
 */
object KeepAliveOpen {
    /** The maker's Security app, which holds the Autostart list and the permissions of each app on Xiaomi phones. */
    internal const val MIUI_SECURITY = "com.miui.securitycenter"
    internal const val MIUI_AUTOSTART = "com.miui.permcenter.autostart.AutoStartManagementActivity"
    internal const val MIUI_PERMISSIONS = "com.miui.permcenter.permissions.PermissionsEditorActivity"

    /** The extra the permissions editor reads the app's package name from. */
    internal const val MIUI_PACKAGE_EXTRA = "extra_pkgname"

    /** What is started: an activity of another app by name (with the app's package under [packageExtra], when it is asked for), or Android's page of the app's details. */
    sealed interface Target {
        data class Component(val packageName: String, val className: String, val packageExtra: String? = null) : Target

        data object AppDetails : Target
    }

    /** Which screen an alternative opens, as the log and the tests name it. */
    enum class Way(val logName: String) {
        AUTOSTART("the Autostart screen"),
        PERMISSIONS("the maker's permissions page of the app"),
        APP_DETAILS("the app's details page"),
    }

    class Alternative(val way: Way, val target: Target)

    /** The alternatives for the phone's family, in the order they are tried; the last is always Android's own page. */
    fun chain(brand: KeepAliveGuide.Brand): List<Alternative> {
        val details = Alternative(Way.APP_DETAILS, Target.AppDetails)
        return when (brand) {
            KeepAliveGuide.Brand.XIAOMI -> listOf(
                Alternative(Way.AUTOSTART, Target.Component(MIUI_SECURITY, MIUI_AUTOSTART)),
                Alternative(Way.PERMISSIONS, Target.Component(MIUI_SECURITY, MIUI_PERMISSIONS, MIUI_PACKAGE_EXTRA)),
                details,
            )
            KeepAliveGuide.Brand.OPPO, KeepAliveGuide.Brand.OTHER -> listOf(details)
        }
    }

    /**
     * Tries the [chain] of [brand] with [start] (which throws when Android cannot start the target, as `startActivity` does) until one starts. Each alternative
     * that does not start is told to [log], and so is the one that did, with its place in the chain. Returns the [Way] that opened, or null when none did.
     */
    // `startActivity` throws ActivityNotFoundException, SecurityException and, on some makers' versions, other runtime exceptions: none may end the button.
    @Suppress("TooGenericExceptionCaught")
    fun open(brand: KeepAliveGuide.Brand, start: (Target) -> Unit, log: (String) -> Unit): Way? {
        val alternatives = chain(brand)
        alternatives.forEachIndexed { i, alternative ->
            val place = "alternative ${i + 1} of ${alternatives.size}"
            try {
                start(alternative.target)
                log("keepalive: opened ${alternative.way.logName} ($place)")
                return alternative.way
            } catch (e: RuntimeException) {
                log("keepalive: ${alternative.way.logName} did not open ($place): ${e.javaClass.simpleName}")
            }
        }
        log("keepalive: none of the ${alternatives.size} alternatives opened")
        return null
    }
}
