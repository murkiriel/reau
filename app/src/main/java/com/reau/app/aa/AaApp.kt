package com.reau.app.aa

import com.reau.app.video.PictureLook
import com.reau.app.video.ScreenNet

/**
 * Which app Android Auto has in front, as far as the picture says ([AaScreenWatch.app]): [SPOTIFY], or [OTHER] (any other app: the phone, a podcast
 * player, Settings...; the net does not tell which, and it is not sure of an app it has never seen), or [NONE] when the screen is the map or the app
 * menu, or [UNKNOWN] when nothing settled yet (the first moments of a session, an app just opened, a net that is not sure). The screen itself
 * ([AaScreen]) is not touched by this: the crop still takes [SPOTIFY] and [OTHER] as the apps.
 */
enum class AaApp {
    SPOTIFY, OTHER, NONE, UNKNOWN;

    /** The picture named an app: [SPOTIFY] or [OTHER]. */
    val named: Boolean get() = this == SPOTIFY || this == OTHER

    companion object {
        /** What one look of the net says of the app in front: the map and the menu are [NONE], an unsure look is [UNKNOWN]. */
        fun of(screen: ScreenNet.Screen): AaApp = when (screen) {
            ScreenNet.Screen.SPOTIFY -> SPOTIFY
            ScreenNet.Screen.OTHER -> OTHER
            ScreenNet.Screen.NAV, ScreenNet.Screen.MENU -> NONE
            ScreenNet.Screen.UNSURE -> UNKNOWN
        }
    }
}

/**
 * Which app the looks of an app settle on: [LOOKS] looks in a row that the net called the same app (Spotify or another), like the three looks of an app that
 * make the screen the apps' ([PictureGate]). A look of a map or the menu, an unsure one, or one of an app the net does not name starts the count over; a look of
 * the other app starts it for that one. Pure: the watch gives the looks that count.
 */
internal class AppGate {
    private var running = AaApp.UNKNOWN
    private var count = 0

    /** The count starts over. */
    fun restart() {
        running = AaApp.UNKNOWN
        count = 0
    }

    /** The app the looks settled on with this look of [kind] that the net called [seen], or null when the count has not got to [LOOKS] (or the look is not of a named app). */
    fun next(kind: PictureLook.Kind, seen: AaApp): AaApp? {
        if (kind != PictureLook.Kind.APPS || !seen.named) {
            restart()
            return null
        }
        if (seen == running) {
            count++
        } else {
            running = seen
            count = 1
        }
        return if (count >= LOOKS) seen else null
    }

    companion object {
        const val LOOKS = PictureGate.OTHER_LOOKS
    }
}
