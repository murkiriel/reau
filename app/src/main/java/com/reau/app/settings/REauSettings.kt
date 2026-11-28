package com.reau.app.settings

import com.reau.app.aa.AaConfig
import com.reau.app.aa.AaVideo
import com.reau.app.dash.BitrateMode
import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.video.AaMargins
import com.reau.app.video.ButtonOverlay
import com.reau.app.video.FrameFit
import com.reau.app.video.RectPx
import com.reau.app.video.StatsOverlay

/**
 * The rider's settings, read when the projection starts. The defaults (AppDefaultsTest): Android Auto (always
 * 800×480, [com.reau.app.aa.AaVideo]) at density 180 with no margins, the apps cropped to 64%, the navigation to 74% and the app menu to 90%
 * in their own places (the owner's own phone, 28/10/2026), the dash's cards and the weather on (the info ring and the maneuver ring off), the joystick's
 * calls and Gemini on, Android Auto's server and the bike's Wi-Fi by themselves; the debug recordings and the detailed log on in a debug build and
 * off in a release. [cardImage] is the picture the card mode
 * sends (a content URI the rider picked, or the measuring grid built in, [DEFAULT_CARD]); null sends the legibility
 * card.
 *
 * The video to the dash (it lagged at speed with more) is one fixed envelope, the same in a debug and in a release build, measured on this dash: the pace of [videoPace] ([fpsCeiling]: 4 frames a second by default, 6 in
 * REau's own pace; the reduced profile is half of it), [streamBitrateBps] 400 kbps at a constant [bitrateMode], a keyframe every [keyframeSec] second, in the 528x304 frame (the profiles are in [com.reau.app.dash.DashConfig]).
 * None of it is a choice: [sanitized] sets it whatever an older build stored.
 */
data class REauSettings(
    val density: Int = DEFAULT_DENSITY,
    val marginW: Int = 0,
    val marginH: Int = 0,
    val fit: Fit = Fit.WHOLE_VISIBLE,
    val cropPercent: Int = DEFAULT_APPS_CROP,
    /**
     * Which part of Android Auto's frame the crop takes, in pixels: the window's top left pixel in the picture, past
     * its edges by all the window but a pixel; null, centred.
     */
    val cropX: Int? = -34,
    val cropY: Int? = 54,
    val moveX: Int = 0,
    val moveY: Int = 0,
    /** The apps' zoom in % (100: as the fit gives it; [FrameFit.ZOOM]). */
    val zoom: Int = 100,
    /** The apps' black bands over the dash's frame ([FrameFit.Cover]). */
    val cover: FrameFit.Cover = FrameFit.Cover.NONE,
    /**
     * The video's pace, the rider's only choice about it ([VideoPace]): Original (A 4 fps / B 2, the factory pace, [DEFAULT_VIDEO_PACE]) or REau (A 6 / B 3, the smoother one). A normal
     * setting in a debug and in a release build, taken at once with the projection on ([com.reau.app.service.LiveTuning.Step.NEW_STREAM]). Only the default is Original
     * (21/11/2026: the dash froze on the bike right after a burst of keyframes, probably from the load on it, and there is no definitive fix yet); whoever saved a pace keeps it.
     */
    val videoPace: VideoPace = DEFAULT_VIDEO_PACE,
    /** The pace of profile A, always [VideoPace.fullFps] of [videoPace] ([sanitized]); nothing else sets it, whatever an older build stored. */
    val fpsCeiling: Int = DEFAULT_VIDEO_PACE.fullFps,
    /** The bitrate ceiling: always [DashConfig.PROFILE_FULL_BPS] ([sanitized]); no choice, whatever an older build stored. */
    val streamBitrateBps: Int = DEFAULT_STREAM_BITRATE,
    val bitrateMode: BitrateMode = BitrateMode.CBR,
    /** 1 or 2 s. A stored 0 ("chosen by the stream itself", which is no more) is still read: [sanitized] makes it 1 s, and the stream would take it as 1 s too. */
    val keyframeSec: Int = DEFAULT_KEYFRAME_SEC,
    val clockSlowPercent: Int = 0,
    /**
     * Optional recordings (28/10/2026), on the phone only: the video sent to the dash and the Android Auto sessions (a recording keeps the
     * session decrypted). Off by default in a release, on in a debug build; the rider switches them in Settings > Debug in both builds, and
     * [sanitized] keeps what was stored in both.
     */
    val recordVideo: Boolean = com.reau.app.BuildConfig.DEBUG,
    val recordSessions: Boolean = com.reau.app.BuildConfig.DEBUG,
    val cardImage: String? = DEFAULT_CARD,
    val mapTheme: MapTheme = MapTheme.AUTO,
    /** The side of the road for the maneuvers Android Auto gives no side or direction to ([DrivingSideMode]); read at each guidance update, so a change counts at once. */
    val drivingSideMode: DrivingSideMode = DrivingSideMode.AUTO,
    val maneuverData: Boolean = true,
    /**
     * The maneuver profile: near a maneuver (< 1000 m) the configured
     * [fpsCeiling]/[streamBitrateBps] apply in full; far, with a 200 m hysteresis, they halve. Needs [maneuverData]
     * on too (it is what feeds the maneuver distance from Android Auto's route); on by default, and fixed on ([sanitized]).
     */
    val maneuverFpsProfile: Boolean = true,
    /**
     * The speed profile, a refinement of [maneuverFpsProfile] — slower (the route's own derived
     * speed, no GPS, [com.reau.app.stream.NavSpeed]) keeps the fuller profile even far from a maneuver; faster drops
     * it. Only has data in the same window as [maneuverFpsProfile] (same feed); when both are on and disagree,
     * [navProfileMode] decides. The km/h band is an estimate. On by default, and fixed on ([sanitized]).
     */
    val speedFpsProfile: Boolean = true,
    /**
     * With [maneuverFpsProfile] and [speedFpsProfile] both on and disagreeing, Otimista
     * takes whichever says the fuller profile (the profiles' own behaviour before this switch existed), Pessimista
     * whichever says the reduced one. Does nothing with zero or one of them on. Pessimista, and fixed so ([sanitized]).
     */
    val navProfileMode: NavProfileMode = NavProfileMode.PESSIMISTA,
    /** Android joins the bike's Wi-Fi by itself (a network suggestion), on by default. */
    val autoJoinWifi: Boolean = true,
    /** Dash packets and Android Auto messages go to the ride log: on by default in a debug build; in a release it starts off and the rider can switch it on. */
    val detailedLog: Boolean = com.reau.app.BuildConfig.DEBUG,
    /** The ride log (and the logcat copy) can be switched off on the screen; on by default. */
    val rideLog: Boolean = true,
    /**
     * REau may use the phone's location (night mode, the dash's Wi-Fi name); on by default, but it only reads anything once the
     * location permission is given, and starts as soon as it is (the place is learned at each return to the screen).
     */
    val useLocation: Boolean = true,
    /** Privacy: logs and recordings older than these days go by themselves; 0 is never ([defaultLogKeepDays]: never in a debug build, 30 in a release). */
    val logKeepDays: Int = defaultLogKeepDays(),
    /**
     * The projection turns itself off after the dash has been gone for this many minutes (the bike switched off and forgotten):
     * one of [NO_DASH_STOP_MINUTES]; 0 is never. 20 by default. Read when a retry comes due, so a change counts from the next one.
     */
    val noDashStopMinutes: Int = DEFAULT_NO_DASH_STOP_MINUTES,
    /**
     * REau leaves at least 2 ms between any two UDP packets to the dash, the video's RTP and the commands together ([com.reau.app.dash.SendPacer]; the owner's decision of
     * 25/11/2026: the dash of some riders restarts a minute after the transmission starts, and the apps that stay up space their sends the same way). On by default; off, the packets
     * leave back to back as before. Read when the session opens its sockets ([com.reau.app.service.LiveSides]), so a change counts from the next projection.
     */
    val pacePackets: Boolean = true,
    /**
     * The joystick answers and ends calls; on by default, but it does nothing until the phone permissions are given (they are asked
     * on the permissions page, or when the rider switches it on again after turning it off).
     */
    val answerCalls: Boolean = true,
    /** Touch Android Auto from the phone's "Tela do Android Auto" (announced next start). */
    val phoneTouch: Boolean = true,
    /** The song on the dash's own music card, on by default; it is sent only with a music control on the joystick's media mode ([com.reau.app.aa.JoystickBindings.mediaControls]). */
    val dashMusic: Boolean = true,
    /** Who is calling on the dash's own call card, on by default. */
    val dashCaller: Boolean = true,
    /** Announce Android Auto's protocol 1.7 instead of 1.2, for the route's total; fixed on ([sanitized]). */
    val aaNewProtocol: Boolean = true,
    /**
     * "Down" in the media mode calls Gemini, and Android Auto may open the microphone; on by default, but the microphone is refused
     * to Android Auto until its permission is given (asked on the permissions page, or when the rider switches it on again).
     */
    val gemini: Boolean = true,
    /** The joystick page's choices, as [com.reau.app.aa.JoystickBindings.encode] writes them; a new install starts with [DEFAULT_JOYSTICK]. */
    val joystick: String = DEFAULT_JOYSTICK,
    /**
     * Each joystick tap that does something shows the action's short name for 1.5 s as the title of the dash's gold band
     * ([com.reau.app.dash.ActionEcho]), so the rider sees what the imprecise joystick really did. On by default; read at each tap, so a change counts at once.
     */
    val actionEcho: Boolean = true,
    /**
     * When the dash's mode changes between its map and its media (and with the first tap of the projection), a card on the dash's video says for
     * 3 s what each direction of the joystick does in that mode, by the rider's page ([com.reau.app.dash.ActionLegend]). Off by default (21/11/2026, the
     * owner's phone's settings made the defaults); read at each tap.
     */
    val joystickLegend: Boolean = false,
    /**
     * A card of REau's own at the top of the dash's video says the phone's music or call volume ("Música 7/15", "Chamada 4/7", "Mudo") for 2 s whenever
     * it changes, from whichever side ([com.reau.app.dash.VolumeCards]); with it on, the volume echo in the title of the gold band is not said any more.
     * On by default; read at each change of the volume.
     */
    val volumeCard: Boolean = true,
    /**
     * A pill of REau's own at the top of the dash's video says who is calling, and the call's hints, for the whole call and 2 s after it
     * ([com.reau.app.dash.CallCards]); with it on, REau no longer sends the dash the state of the call and the caller's name, so the dash does not open its own call
     * screen (which covers almost all of the video), and the joystick's sides do nothing on a call. On by default; read at each change of the call and of the settings.
     */
    val callCard: Boolean = true,
    /**
     * A minute after the joystick, one touch takes Android Auto's blue focus
     * away ([com.reau.app.aa.FocusClearer]); on by default. Taken at the next start.
     */
    val clearAaFocus: Boolean = true,
    /** The dash's mask over the "Tela do Android Auto" (video/DashMask). On by default. */
    val dashMask: Boolean = true,

    /**
     * The important notices are also spoken, by Android's own voice, in the rider's headset ([com.reau.app.service.RiderVoice]): the dash gone or stuck and
     * back, the phone hot, the phone's Wi-Fi off. Off by default; with it off nothing is spoken. Read at each phrase, so a change counts at once.
     */
    val spokenAlerts: Boolean = false,

    /** The app menu's own fit; null, the apps'. */
    val menuFit: FitParams? = DEFAULT_MENU_FIT,
    /**
     * The menu's own fit as it was last set, kept while [menuFit] is off: switching it on again (a stray tap, say)
     * gives this back, never a copy of the apps' fit that happens to be on the screen. Follows [menuFit] while that is on
     * ([sanitized]); the default menu fit until one was ever set.
     */
    val menuFitKept: FitParams = DEFAULT_MENU_FIT,
    /** The navigation apps' (Waze, Google Maps) own fit, always theirs and never the apps' (the apps' default until one was set). */
    val navFit: FitParams = DEFAULT_NAV_FIT,
    /**
     * The stream's FPS and bitrate written in red, with a black border, on the dash's
     * video ([StatsOverlay]); off by default, and offered on a debug build only. Its place and size (in the dash frame's pixels)
     * are set on the "Tela do Android Auto", and only offered there while this is on.
     */
    val statsOverlay: Boolean = false,
    val statsX: Int = StatsOverlay.DEFAULT_X,
    val statsY: Int = StatsOverlay.DEFAULT_Y,
    val statsSize: Int = StatsOverlay.DEFAULT_SIZE,
    val buttonOverlay: Boolean = false,
    val buttonX: Int = ButtonOverlay.DEFAULT_X,
    val buttonY: Int = ButtonOverlay.DEFAULT_Y,
    val buttonSize: Int = ButtonOverlay.DEFAULT_SIZE,
    /**
     * The FPS test card ([com.reau.app.video.FpsTestCard]): the picture sent to the dash is only a numbered test card, at the pace of [fpsTestCardFps]
     * whatever Android Auto sends, to film the dash and count the frames. Off by default and a debug build's only, like the two texts above.
     */
    val fpsTestCard: Boolean = false,
    /** The pace of the test card while it is on, one of [FPS_TEST_CARD_RATES] ([sanitized] brings it to the nearest); it does not touch the pace of the normal video. */
    val fpsTestCardFps: Int = DEFAULT_FPS_TEST_CARD_FPS,
    /**
     * The dash's heartbeat carries the phone's real battery, charging, GPS
     * and cell signal ([com.reau.app.service.PhoneStatusReader]) instead of the capture's 62 % and always-on GPS and
     * signal. On by default; the switch is there to go back if the joystick or the video ever act up with it.
     */
    val dashPhoneStatus: Boolean = true,
    /**
     * The album's picture on the dash's music card (JPEG 75x75, in slices), when Android
     * Auto hands it over. On by default (with the music card, which needs a music control on the joystick's media mode).
     */
    val dashAlbumArt: Boolean = true,
    /**
     * The dash's own maneuver ring: off by default (the dash is cleaner). Off, REau sends the code that hides it (0xFF) while guiding and
     * with no route ([com.reau.app.dash.DashSession.maneuverCircleEnabled]), except in Waze and Google Maps; with the ring hidden the dash
     * stops passing the map mode's buttons on ([com.reau.app.dash.DashIdleCard]).
     */
    val maneuverRing: Boolean = false,
    /**
     * "Clima no painel", screen "Dados da moto": on, REau asks Open-Meteo (no key, no
     * account; the rounded place of "Usar a localização") for the weather and tells the dash the temperature (`06 10`) and
     * the icon (`06 08`). On by default (28/10/2026), but it fetches nothing until the location is known (the permission given): it is the
     * one thing REau sends to the internet. Off, the dash gets neither (no made-up temperature).
     */
    val dashWeather: Boolean = true,
    /**
     * The info ring on the dash's video, on the right where the dash draws nothing (rain, wind, storms, UV, air quality, the sun and
     * the day's temperatures, [com.reau.app.weather.InfoRing]). On by default since 21/11/2026 (the owner's phone's settings made the defaults),
     * written in the title ([infoTitle]) so the video is left alone; from 30/10/2026 it was the rider's to switch on, because drawn on the video
     * it covers Waze's speed limit and speed (it was on from 28/10/2026). On, it has nothing to show until the location is
     * known: it asks the internet for the weather, as "Clima no painel" does, which it does not need on: the ring asks for the weather by itself.
     */
    val infoRing: Boolean = true,
    /**
     * With the info ring: which of its data it may say (bits of [com.reau.app.weather.RingData]). All of them by default since 21/11/2026 (the owner's
     * phone's settings made the defaults); from 12/11/2026 it was only the rain, the wind and the thunder ([com.reau.app.weather.RingData.DEFAULT]).
     */
    val infoRingData: Int = com.reau.app.weather.RingData.ALL,
    /** With the info ring: it turns from one page to the next every [infoRingSeconds] (on), or stays on the most urgent one (off). */
    val infoRingCarousel: Boolean = true,
    val infoRingSeconds: Int = com.reau.app.weather.RingOptions.DEFAULT_SECONDS,
    /** With the info ring: it sits in the place of the dash's maneuver ring (on the left) instead of on the right; the two take turns ([com.reau.app.dash.RingCycle]). */
    val infoRingInPlace: Boolean = false,
    /**
     * With the info ring on (P31 Título, 18/11/2026): its pages are written in the title of the dash's gold band, turning with the street (or
     * "REau"), and the ring is not drawn on the video: one or the other. On by default since 21/11/2026 (the owner's phone's settings made the defaults).
     */
    val infoTitle: Boolean = true,
    /**
     * A server on the local network that shows Android Auto in a
     * browser ([com.reau.app.web.WebBridge]). Off by default, and only up while the projection runs.
     */
    val webView: Boolean = false,
    /** With [webView], the browser may also touch Android Auto. Off by default: the browser only looks. */
    val webTouch: Boolean = false,
    /**
     * Where Android Auto's sound goes while the browser switch is on: the phone only (as always: the navigation
     * voice reaches the rider's headset), the browser only, or both. Anything but the phone only needs the media and guidance sinks
     * announced to Android Auto ([audioSinks]).
     */
    val webAudio: WebAudioMode = WebAudioMode.PHONE,
    /**
     * The picture size of the stream, by its id: no choice and not stored (the old `video_frame` key is ignored), [sanitized] sets it to [DashConfig.VIDEO_FRAME] (528x304) in every build.
     * It is the AREA the fit is made for ([mapping]) and the video is composed in, 1:1. Kept as a field so that the fall back to 526x300 when no encoder takes 528x304
     * ([com.reau.app.service.LiveSides]) can say so, and the log lists it.
     */
    val videoFrame: String = DashConfig.VIDEO_FRAME.id,
    /**
     * "Anunciar como moto", in every build and on by default (20/11/2026; it was a debug build's experiment before): the head unit tells Android Auto it is a MOTORCYCLE,
     * under a vehicle id of its own ([com.reau.app.aa.ServiceDiscoveryResponse.VEHICLE_ID_MOTORCYCLE]), see [aaConfig]. It counts from the next Android Auto session.
     * Off, the announcement is the one it always was (a car).
     */
    val announceMotorcycle: Boolean = true,
    /**
     * "Modo de bloqueio do REau (teste)", only in the 0.3.9x test builds, on by default since 0.3.100 (the owner's decision of 28/11/2026; it was off from 0.3.96 to 0.3.99): one switch for the whole function. On, it shows the main screen's button
     * "Bloquear a tela", turns the screen on again, for 10 s, when the display goes off during the projection (so that the phone's own task killer, realme or Xiaomi, never
     * finds "screen off and app in the background", [com.reau.app.service.ScreenKeeper]) and shows REau's own screen over the lock screen when it lights ([com.reau.app.ui.LockOverHost],
     * only if REau was in front). Read at each screen-off and at each look at the main screen, so a change counts at once.
     */
    val lockMode: Boolean = true,
    /**
     * Once a day, with the internet on, REau asks GitHub (the same single request as the "Check now" button, [com.reau.app.update.ReleaseCheck]) whether a newer version is
     * published, and posts one notification per version ([com.reau.app.update.UpdateNotice]). Nothing is downloaded or installed. On by default; off cancels the daily job.
     */
    val updateNotice: Boolean = true,
) {
    /** The size the stream's encoder is built for: [videoFrame] after [sanitized] (528x304; the dash's own 526x300 only after the fall back, when no encoder takes 528x304). */
    val frameSize: DashConfig.FrameSize
        get() = if (videoFrame == DashConfig.VIDEO_FRAME.id) DashConfig.VIDEO_FRAME else DashConfig.FULL_FRAME

    /** The stats text as the compositor takes it: never on a release build, whatever a phone saved (its switch is a debug build's only). */
    val overlay: StatsOverlay get() = StatsOverlay(statsOverlay && com.reau.app.BuildConfig.DEBUG, statsX, statsY, statsSize)

    /** The last-button text on the dash's video: debug builds only, like [overlay]. */
    val buttonLook: ButtonOverlay get() = ButtonOverlay(buttonOverlay && com.reau.app.BuildConfig.DEBUG, buttonX, buttonY, buttonSize)

    /** The pace the test card forces on the stream, or null (the normal pace): only with the card on in a debug build, like [overlay]. */
    val fpsTestRate: Int? get() = if (fpsTestCard && com.reau.app.BuildConfig.DEBUG) nearestFpsTestRate(fpsTestCardFps) else null

    /** The apps' fit (Waze, Google Maps, Spotify...), the fields of before. */
    val appsFit: FitParams get() = FitParams(fit, cropPercent, cropX, cropY, moveX, moveY, zoom, cover)

    fun withAppsFit(p: FitParams): REauSettings =
        copy(
            fit = p.fit, cropPercent = p.cropPercent, cropX = p.cropX, cropY = p.cropY, moveX = p.moveX, moveY = p.moveY,
            zoom = p.zoom, cover = p.cover,
        )

    /** The part of Android Auto's frame left by the margins, where the fit takes the picture from. */
    val content: RectPx get() = AaMargins(marginW, marginH).contentRect(AaVideo.WIDTH, AaVideo.HEIGHT)


    /** The weather is fetched and kept for the dash's temperature and icon ([dashWeather]) or for the info ring ([infoRing]). */
    val weatherWanted: Boolean get() = dashWeather || infoRing

    /** The rider's choices for the info ring, in the form its rules take. */
    val infoRingOptions: com.reau.app.weather.RingOptions
        get() = com.reau.app.weather.RingOptions(infoRingData, infoRingCarousel, infoRingSeconds)

    /** The navigation apps' mapping: their own fit. */
    val navMapping: FrameFit.Mapping get() = mappingOf(navFit)

    /**
     * The mapping for the screen Android Auto shows (com.reau.app.aa.AaScreens): two crops only, the navigation apps' (Waze, Google Maps)
     * and the rest's (the app menu and every other app, and a screen not known yet).
     */
    fun mappingFor(screen: com.reau.app.aa.AaScreen): FrameFit.Mapping = when (screen) {
        com.reau.app.aa.AaScreen.NAV -> navMapping
        else -> mapping
    }

    /**
     * Whether Android Auto is told REau takes its sound: with any mode that is not the phone only, whatever the browser switch says. The
     * switch left out of this on purpose: the sinks are only told when a session begins, so tying them to the switch
     * took a new Android Auto session each time the switch went on or off.
     */
    val audioSinks: Boolean get() = webAudio != WebAudioMode.PHONE

    /**
     * With the sinks announced Android Auto no longer plays the sound itself, so REau plays it on the phone, unless the browser alone is to
     * have it (switch on): with the switch off nobody in a browser would hear it, and the phone must not go mute.
     */
    val soundOnPhoneViaReau: Boolean get() = audioSinks && (webAudio == WebAudioMode.BOTH || !webView)

    val aaConfig: AaConfig get() = AaConfig(
        density, AaMargins(marginW, marginH), navigation = maneuverData, touch = phoneTouch, audio = audioSinks,
        media = dashMusic, phone = dashCaller,
        protocolMinor = if (aaNewProtocol) com.reau.app.aa.Messages.MINOR_NEWER else com.reau.app.aa.Messages.MINOR_DEFAULT,
        motorcycle = announceMotorcycle,
    )

    /**
     * Which part of Android Auto's frame goes where on the dash's frame: the fit, then the picture moved by
     * [moveX] (right) and [moveY] (down) dash pixels, negative for left and up.
     */
    val mapping: FrameFit.Mapping get() = mappingOf(appsFit)

    private fun mappingOf(p: FitParams): FrameFit.Mapping {
        val fitted = when (p.fit) {
            Fit.WHOLE_VISIBLE -> FrameFit.wholeVisible(content, frameSize)
            Fit.CROP -> FrameFit.cropAt(content, p.cropPercent, p.cropX, p.cropY, frameSize)
        }
        return FrameFit.zoomed(FrameFit.moved(fitted, p.moveX, p.moveY, frameSize), p.zoom).copy(cover = p.cover)
    }

    /** Whether a running projection must be given a new [mapping] after these settings became [other]: the fit
     *  and everything that places the picture (to adjust it while looking at the dash). */
    fun refitNeeded(other: REauSettings): Boolean = com.reau.app.aa.AaScreen.entries.any { mappingFor(it) != other.mappingFor(it) }

    /**
     * What changed from these settings to [other], one "name: old -> new" per field (the log says which
     * setting the rider changed, from what to what). Read from the data class's own text, so a new field is covered
     * without a list to keep up to date. No setting holds a place or a route; the one that holds a file's address (the picture) is said
     * only as none, default or picked.
     */
    fun changesTo(other: REauSettings): List<String> =
        changes(fieldsOf(toString()) - KEPT_MENU_FIT, fieldsOf(other.toString()) - KEPT_MENU_FIT, "")

    /** A field that holds another data class on both sides (the menu's [FitParams]) is said field by field, as
     *  `menuFit.cropX`; a side with none (null) is said whole. */
    private fun changes(mine: Map<String, String>, theirs: Map<String, String>, prefix: String): List<String> =
        theirs.flatMap { (name, value) ->
            val old = mine[name]
            when {
                old == null || old == value -> emptyList()
                nested(old) && nested(value) -> changes(fieldsOf(old), fieldsOf(value), "$prefix$name.")
                // The picture is a file of the rider's: its address (a folder, a file name) is not written, as in the settings line.
                name == "cardImage" -> listOf("$prefix$name: ${SettingsInUse.card(old)} -> ${SettingsInUse.card(value)}")
                else -> listOf("$prefix$name: $old -> $value")
            }
        }

    private fun nested(value: String) = value.firstOrNull()?.isUpperCase() == true && value.endsWith(")") && '(' in value

    /** The fields of a data class's text, split at the commas outside any inner one's parentheses. */
    internal fun fieldsOf(text: String): Map<String, String> {
        val body = text.substringAfter('(').removeSuffix(")")
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        body.forEachIndexed { i, c ->
            if (c == '(') depth++
            if (c == ')') depth--
            if (depth == 0 && body.startsWith(", ", i)) {
                parts += body.substring(start, i)
                start = i + 2
            }
        }
        parts += body.substring(start)
        return parts.mapNotNull { part ->
            part.indexOf('=').takeIf { it > 0 }?.let { part.substring(0, it) to part.substring(it + 1) }
        }.toMap()
    }

    /**
     * Every value brought into what is offered. Two are no longer choices: the
     * video clock runs at 0% slower and touching Android Auto from the phone is native (the frame size and the
     * instrument cluster experiment went from the code itself); the newer protocol and the focus clearing are fixed on. The two texts over
     * the dash's video are kept only when [debugBuild] is true (the recordings are kept as stored in both builds). Five more are fixed, in a debug and in a release build alike, the stream's
     * rate ceiling, bitrate, picture, bitrate mode and keyframe: profile A is 4 fps (6 in REau's pace) / 400 kbps / constant / 1 s in 528x304 and profile B,
     * its half in pace, 2 fps (3 in REau's pace) (the navigation profiles choose between them, [DashConfig.PROFILE_FULL_FPS]); whatever an older build stored for the pace, the ceiling or the
     * picture (`video_fps`, `video_cap_bps`, `video_frame`, `video_profile`, `video_pace_fps`, `experiment_fps63`, `fps_ceiling`, `stream_bitrate`) is ignored. The
     * maneuver data and the two navigation profiles (by maneuver, by speed) are always on, the pessimistic one winning when they disagree
     * ([navProfileMode]).
     */
    fun sanitized(debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG): REauSettings {
        // The video is fixed (plan 32, 20/11/2026): whatever an older build stored for the pace, the ceiling or the picture is ignored, in a debug and in a release build.
        val framed = copy(
            // The two texts over the dash's video are a debug build's: a value left on by one build must not draw in the other.
            statsOverlay = statsOverlay && debugBuild,
            buttonOverlay = buttonOverlay && debugBuild,
            // The test card is a debug build's too, and its pace is one of the offered.
            fpsTestCard = fpsTestCard && debugBuild,
            fpsTestCardFps = nearestFpsTestRate(fpsTestCardFps),
            videoFrame = DashConfig.VIDEO_FRAME.id,
            clockSlowPercent = 0,
            phoneTouch = true,
            density = allowedDensity(density),
            marginW = marginW.coerceIn(0, AaVideo.WIDTH - MIN_PICTURE),
            marginH = marginH.coerceIn(0, AaVideo.HEIGHT - MIN_PICTURE),
            navProfileMode = NavProfileMode.PESSIMISTA,
            maneuverData = true,
            clearAaFocus = true,
            aaNewProtocol = true,
            maneuverFpsProfile = true,
            speedFpsProfile = true,
            fpsCeiling = videoPace.fullFps,
            streamBitrateBps = DashConfig.PROFILE_FULL_BPS,
            bitrateMode = BitrateMode.CBR,
            keyframeSec = DashConfig.PROFILE_KEYFRAME_SEC,
            logKeepDays = logKeepDays.takeIf { it in LOG_KEEP_DAYS } ?: defaultLogKeepDays(debugBuild),
            noDashStopMinutes = noDashStopMinutes.takeIf { it in NO_DASH_STOP_MINUTES } ?: DEFAULT_NO_DASH_STOP_MINUTES,
            statsX = statsX.coerceIn(StatsOverlay.X),
            statsY = statsY.coerceIn(StatsOverlay.Y),
            statsSize = statsSize.coerceIn(StatsOverlay.SIZE),
            buttonX = buttonX.coerceIn(ButtonOverlay.X),
            buttonY = buttonY.coerceIn(ButtonOverlay.Y),
            buttonSize = buttonSize.coerceIn(ButtonOverlay.SIZE),
            infoRingData = infoRingData and com.reau.app.weather.RingData.ALL,
            infoRingSeconds = infoRingSeconds.coerceIn(com.reau.app.weather.RingOptions.SECONDS),
        )
        val content = framed.content
        val area = framed.frameSize
        val menu = framed.menuFit?.within(content, area)
        return framed.withAppsFit(appsFit.within(content, area)).copy(
            menuFit = menu, menuFitKept = menu ?: framed.menuFitKept.within(content, area),
            navFit = framed.navFit.within(content, area),
        )
    }

    /** A fit's values brought into what is offered; the crop's place within FrameFit.cropRange. */
    private fun FitParams.within(content: RectPx, area: DashConfig.FrameSize): FitParams {
        val percent = cropPercent.coerceIn(CROP)
        val (rangeX, rangeY) = FrameFit.cropRange(content, percent, area)
        return copy(
            cropPercent = percent,
            cropX = cropX?.coerceIn(rangeX),
            cropY = cropY?.coerceIn(rangeY),
            moveX = moveX.coerceIn(MOVE),
            moveY = moveY.coerceIn(MOVE),
            zoom = zoom.coerceIn(FrameFit.ZOOM),
            cover = cover.clamped(),
        )
    }

    companion object {
        /**
         * The pace a fresh install starts with: Original (A 4 fps / B 2). On 21/11/2026 the dash froze on the bike right after a burst of keyframes; the cause is probably the load on the
         * dash and there is no definitive fix yet, so the factory pace is the lighter one while REau's (6/3) stays one tap away. Only the default: a pace the rider saved is kept.
         */
        val DEFAULT_VIDEO_PACE: VideoPace = VideoPace.ORIGINAL

        /**
         * While Android Auto's app menu or another app is in front, the pace to the dash goes up to [VideoPace.lightScreenFps], profile A's pace of [pace] (6 fps in
         * REau, 4 in Original; never above 6, and profile B's pace on the navigation, which alone follows the profiles), so a joystick touch shows sooner: always, in a debug build and in a
         * release (28/10/2026: it was a debug switch, now it has none). Tried on the bike in a debug build on 28/10/2026.
         */
        fun lightScreenFps(pace: VideoPace = DEFAULT_VIDEO_PACE): Int = pace.lightScreenFps

        /** Privacy: the days offered for the logs, and 0 (never). */
        val LOG_KEEP_DAYS = listOf(7, 14, 30, 0)

        /** What the rider can pick for [noDashStopMinutes]: 5, 10 or 20 minutes, or 0 (never). */
        val NO_DASH_STOP_MINUTES = listOf(5, 10, 20, 0)
        private const val DEFAULT_NO_DASH_STOP_MINUTES = 20
        private const val DEFAULT_LOG_KEEP_DAYS = 30

        /** The days the logs are kept on a new install: never (0) in a debug build, made to be read after a long test, 30 in a release. */
        fun defaultLogKeepDays(debugBuild: Boolean = com.reau.app.BuildConfig.DEBUG): Int = if (debugBuild) 0 else DEFAULT_LOG_KEEP_DAYS

        /** The Android Auto DPIs the rider can pick (plan 32, 20/11/2026); 180 is the default. */
        val DENSITIES = listOf(180, 200, 220)

        /** [value] when it is one of [DENSITIES]; anything else goes back to the default, 180 (it is not rounded to a neighbour). */
        fun allowedDensity(value: Int): Int = if (value in DENSITIES) value else DEFAULT_DENSITY

        /**
         * The paces the FPS test card is offered at: the four the video really runs at (Original 4 and 2 fps, REau 6 and 3 fps), never above 6
         * (the project's rule since 20/11/2026: the dash failed above it on the bike; the 8, 10 and 12 of the first card went on 25/11/2026).
         */
        val FPS_TEST_CARD_RATES = listOf(2, 3, 4, 6)

        /** The offered pace nearest to [value]; the lower one on a tie (5 gives 4, 7 gives 6, 0 gives 2, 13 gives 6). */
        fun nearestFpsTestRate(value: Int): Int = FPS_TEST_CARD_RATES.minBy { kotlin.math.abs(it.toLong() - value) }

        const val DEFAULT_FPS_TEST_CARD_FPS = 6

        val FPS = 4..30
        val CROP = 20..100

        // The crop and the move go one by one, for the finest fit. The crop's place in
        // pixels, one by one and ten by ten.
        const val STEP_CROP_POS = 1
        const val STEP_CROP_POS_BIG = 10
        const val STEP_CROP = 1

        /** How far the picture can be moved on the dash, each way, in dash pixels (the frame is 526×300). */
        val MOVE = -150..150
        val CLOCK = listOf(0, 3, 5, 8)
        const val STEP_MARGIN = 10
        const val STEP_MOVE = 1
        private const val DEFAULT_DENSITY = 180
        private const val DEFAULT_CROP = 74
        private const val DEFAULT_APPS_CROP = 64

        private const val DEFAULT_STREAM_BITRATE = DashConfig.PROFILE_FULL_BPS
        private const val DEFAULT_KEYFRAME_SEC = DashConfig.PROFILE_KEYFRAME_SEC

        /**
         * The default joystick page, the owner's own (28/10/2026): in the media mode the sides move the focus and the centre confirms (the
         * page's own defaults, not written here), up is the app menu and down Gemini; in the map mode the sides zoom out and in, and down is
         * the info ring's switch (also not written). No music control in the media mode: the dash gets no music card until one is put there.
         */
        const val DEFAULT_JOYSTICK = "media.up.once=HOME;media.down.once=ASSISTANT;map.left.once=ZOOM_OUT;map.right.once=ZOOM_IN"

        /**
         * The default card, the 526×300 measuring grid, built in (res/drawable-nodpi). The package in it is the full
         * edition's: a card shown reads the app's own (LiveSides.cardPicture).
         */
        const val DEFAULT_CARD = "android.resource://com.reau.app/drawable/grade_medicao"

        /** The field of the menu fit kept while off: not said in the change lines, [menuFit] is. */
        private const val KEPT_MENU_FIT = "menuFitKept"

        /**
         * The navigation apps' default fit: where Waze's and Google Maps' own fit starts (the owner's phone's: 25/10/2026, and 30/10/2026 with the
         * window's left at 90, which is what the phone ended up with after the rider's own adjusting; read from its settings, not from this code).
         */
        val DEFAULT_NAV_FIT = FitParams(Fit.CROP, DEFAULT_CROP, 90, 62, 0, 0)

        /** The default app menu fit (the owner's phone's, 25/10/2026). */
        val DEFAULT_MENU_FIT = FitParams(Fit.WHOLE_VISIBLE, 90, 32, 20, 20, 0)

        /** The least picture margins may leave on an axis. */
        private const val MIN_PICTURE = 16
    }
}

/** The menu's own fit switched [on] (the one kept, never a copy of the apps') or off (the apps' are used). */
fun REauSettings.withMenuFitOwn(on: Boolean): REauSettings = copy(menuFit = if (on) menuFitKept else null)

/** The menu tab's "restore": the menu's default fit, also as the one kept while its own fit is off. */
fun REauSettings.withMenuFitReset(): REauSettings =
    copy(menuFit = menuFit?.let { REauSettings.DEFAULT_MENU_FIT }, menuFitKept = REauSettings.DEFAULT_MENU_FIT)
