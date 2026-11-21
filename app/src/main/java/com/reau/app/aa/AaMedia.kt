package com.reau.app.aa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Android Auto's playback status says now: [playing] (the status names the state PLAYING; paused, stopped and nothing said are all not playing),
 * and [source], the music app it names ("Spotify"), null while it has named none. Never the song: only the app and the state, so the ride log can carry it.
 */
data class MediaNow(val playing: Boolean = false, val source: String? = null) {
    /** The status names Spotify (Android Auto writes the name of the app, such as "Spotify"). */
    val fromSpotify: Boolean get() = source?.contains("spotify", ignoreCase = true) == true
}

/** The one watcher of the process, fed by the music status of the session ([AapCards]) and read by whoever needs to know what plays. */
object AaMedia {
    val watch = MediaNowWatch { com.reau.app.data.RideDiagnostics.log("aa-media", it) }
}

/**
 * The state and the app of the music Android Auto says is playing ([now]). Each change is said once through [say], for the ride log, without the song.
 * Android Auto repeats the status about once a second: a repeat changes nothing and says nothing.
 */
class MediaNowWatch(private val say: (String) -> Unit) {
    private val current = MutableStateFlow(MediaNow())
    val now: StateFlow<MediaNow> = current.asStateFlow()

    /**
     * A playback status: [source], the app it names (null when it names none, the last one stays), and [playing], whether the state is PLAYING (null when
     * it says no state, the last one stays).
     */
    @Synchronized
    fun playback(source: String?, playing: Boolean?) {
        val before = current.value
        val after = MediaNow(playing ?: before.playing, source ?: before.source)
        if (after == before) return
        current.value = after
        say("aa-media: ${if (after.playing) "playing" else "not playing"}, source ${after.source ?: "unknown"}")
    }

    /** The session ended: no music is playing from it any more, and the next session names its own app. */
    @Synchronized
    fun reset() {
        if (current.value == MediaNow()) return
        current.value = MediaNow()
        say("aa-media: cleared (the session ended)")
    }
}

/**
 * Whether Spotify is what Android Auto shows in front, from the picture and from the music status. Spotify as the net sees it ([AaApp.SPOTIFY]) is. When the net is not
 * sure of the app ([AaApp.UNKNOWN]) but the screen is an app's and Android Auto says Spotify is playing, it is Spotify as well (the music app that plays is the one
 * most likely to be open). Another app the net names ([AaApp.OTHER]) is not, nor is the map or the menu ([AaApp.NONE]), whatever plays.
 */
fun spotifyInFront(screen: AaScreen, app: AaApp, media: MediaNow): Boolean = when (app) {
    AaApp.SPOTIFY -> true
    AaApp.UNKNOWN -> screen == AaScreen.APPS && media.playing && media.fromSpotify
    AaApp.OTHER, AaApp.NONE -> false
}
