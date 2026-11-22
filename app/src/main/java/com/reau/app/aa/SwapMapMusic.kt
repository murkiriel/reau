package com.reau.app.aa

import com.reau.app.dash.EchoLabel
import com.reau.app.video.ScreenNet

/**
 * The joystick action "switch map and music" ([JoystickBindings.Action.SWAP_MAP_MUSIC]): with the map in front it opens the last music app (Android Auto's
 * own media button), and with anything else in front (Spotify, the app menu, another app, a screen not known) it opens the last navigation app (its
 * navigation button). The net only knows Spotify as music; a music app it does not know (YouTube Music, say) is [ScreenNet.Screen.OTHER], and "anything else
 * goes to the map" covers it.
 *
 * What is in front is read from the screen watch ([AaScreens.watch]: [AaScreenWatch.screen] and [AaScreenWatch.app]), the state the net's looks settle on
 * ([AaScreenWatch.look], fed by the compositor's thread) that the music mode ([MusicMode]) and the light-screen pace ([com.reau.app.stream.LightScreen])
 * already read. [ScreenNet.classify] itself is not called here: it is not thread-safe, its buffers belong to the compositor's thread. The watch needs a few
 * looks to settle (two of a map, three of anything else, after a wait the keys start), so a second tap right after the first, before the picture changed,
 * sends the same key again, which opens what is already opening and does no harm.
 *
 * Pure: what is in front is given ([screen], [app]) and so is the log, so the JVM tests run it with no phone.
 */
class SwapMapMusic(
    private val screen: () -> AaScreen,
    private val app: () -> AaApp,
    private val log: (String) -> Unit,
) {
    /** Where a tap goes: the music app or the navigation app. */
    enum class Target(val keycode: Int, val echo: EchoLabel) {
        MUSIC(ServiceDiscoveryResponse.KEY_MEDIA, EchoLabel.SWAP_TO_MUSIC),
        MAP(ServiceDiscoveryResponse.KEY_NAVIGATION, EchoLabel.SWAP_TO_MAP),
    }

    /** Where the last tap went, for the echo in the title of the dash's gold band ([echoFor]); null before any tap. */
    @Volatile var lastTarget: Target? = null
        private set

    /** One tap: the key to send for what is in front now, and a line in the log (the screen the net named and where it goes; never an app's or a song's name). */
    fun resolve(): AaCommand.Key {
        val front = seen(screen(), app())
        val target = targetFor(front)
        lastTarget = target
        log(logLine(front, target))
        return AaCommand.Key(target.keycode)
    }

    companion object {
        /** The net's own word for what the watch settled on: the map, the menu, Spotify, another app, or not sure ([AaScreen.UNKNOWN]). */
        fun seen(screen: AaScreen, app: AaApp): ScreenNet.Screen = when (screen) {
            AaScreen.NAV -> ScreenNet.Screen.NAV
            AaScreen.MENU -> ScreenNet.Screen.MENU
            AaScreen.APPS -> if (app == AaApp.SPOTIFY) ScreenNet.Screen.SPOTIFY else ScreenNet.Screen.OTHER
            AaScreen.UNKNOWN -> ScreenNet.Screen.UNSURE
        }

        /**
         * Whether the music is already in front: only Spotify is known as music by the net ([ScreenNet.Screen.SPOTIFY]). The map centre that opens the music
         * ([JoystickMap.mapCenterOpensMusic]) sends nothing then, as another media key would change app or open the picker.
         */
        fun musicInFront(seen: ScreenNet.Screen): Boolean = seen == ScreenNet.Screen.SPOTIFY

        /** The decision: the map goes to the music, and everything else (Spotify, the menu, another app, not sure) goes to the map. */
        fun targetFor(seen: ScreenNet.Screen): Target = if (seen == ScreenNet.Screen.NAV) Target.MUSIC else Target.MAP

        /** The echo label to show for a press whose label is [label], once the tap went to [target]: the swap says its destination, the others say themselves. */
        fun echoFor(label: EchoLabel, target: Target?): EchoLabel = if (label == EchoLabel.SWAP_MAP_MUSIC && target != null) target.echo else label

        /** The log line of a tap: `joystick swap: screen NAV -> music`, `joystick swap: screen SPOTIFY -> map`. */
        fun logLine(seen: ScreenNet.Screen, target: Target): String = "joystick swap: screen ${seen.name} -> ${target.name.lowercase()}"
    }
}
