package com.reau.app.aa

import com.reau.app.video.PictureLook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which of Android Auto's screens is shown, by the display it draws it on: its app menu, a navigation app (Waze, on the navigation
 * templates' display, or Google Maps, on its own), any other app (Spotify, the phone…), or not known. Each of the first three has its
 * own crop.
 */
enum class AaScreen { MENU, NAV, APPS, UNKNOWN }

/**
 * As measured on a device, Android Auto 17.9 draws
 * each of its screens on a virtual display of its own, all open at once ("GhostActivityDisplay:ComponentInfo{…}"); the
 * one shown is the one with the focused window. The app menu is GhAppLauncherService's display, Waze's (the navigation
 * templates') TemplateNavigationService's, Google Maps' GmmCarProjectionService's (both are navigation, one crop); any other
 * of Android Auto's displays is another app (Spotify, the phone). Only the name counts: the numbers change each session.
 */
object AaScreenRule {
    /**
     * A display: its number, its name (DisplayManager) and whether one of its windows has focus. [active], whether one is the system's
     * active window, is only logged: on 22/10/2026 it stayed on an app's template display for minutes while Waze and Google Maps came
     * and went, so it says nothing of what is in front.
     */
    data class Display(val id: Int, val name: String, val focused: Boolean, val active: Boolean = false)

    const val PROJECTED = "GhostActivityDisplay"

    /** A display that flashes up (and takes the focus for a moment) on every change and is no app's screen: it never counts. */
    const val TRAMPOLINE = "ProjectionTrampolineFallbackService"
    const val LAUNCHER = "GhAppLauncherService"

    /** The display the navigation apps' templates are drawn on (Waze); other navigation apps of that category share it. */
    const val NAVIGATION = "TemplateNavigationService"

    /** Google Maps' own display ([OWN_DISPLAY_APPS]). */
    const val MAPS = "GmmCarProjectionService"

    /**
     * The apps whose screen is a display of their own process
     * (Google Maps' GmmCarProjectionService), not of Android Auto's: the accessibility service hears their window
     * changes too (res/xml/aa_server_accessibility.xml), or it never learns that their screen took the focus (measured
     * on a phone: Maps took it and REau heard nothing, so the menu's fit stayed on the map).
     */
    val OWN_DISPLAY_APPS: Set<String> = setOf("com.google.android.apps.maps")

    /** The screen the focused display of Android Auto shows; UNKNOWN with none (the phone's screen, the side bar). */
    fun of(displays: List<Display>, focusedBefore: Set<Int> = emptySet(), shownBefore: Int? = null): AaScreen =
        screenOf(shown(displays, focusedBefore, shownBefore))

    fun screenOf(display: Display?): AaScreen = display?.let {
        when {
            LAUNCHER in it.name -> AaScreen.MENU
            NAVIGATION in it.name || MAPS in it.name -> AaScreen.NAV
            else -> AaScreen.APPS
        }
    } ?: AaScreen.UNKNOWN

    /**
     * The focused display of Android Auto, if any (the trampoline's flicker does not count). Since
     * Google Maps is heard its display may stay focused while another takes the focus too: the one not focused in the reading before
     * ([focusedBefore]) has just taken it and wins; with none new, the one shown before ([shownBefore]) while it keeps
     * the focus; else the newest display. With none focused but a single display of Android Auto in the reading, that one is the screen: the session
     * had just started with Google Maps in front (23/10/2026: the reading was `GmmCarProjectionService` alone, not focused, the screen stayed unknown
     * and kept the apps' crop on the map); with several displays and none focused nothing is said.
     */
    fun shown(displays: List<Display>, focusedBefore: Set<Int> = emptySet(), shownBefore: Int? = null): Display? {
        val focused = displays.filter { it.focused && counts(it) }
        return focused.lastOrNull { it.id !in focusedBefore } ?: focused.firstOrNull { it.id == shownBefore } ?: focused.lastOrNull()
            ?: displays.filter { counts(it) }.singleOrNull()
    }

    /** The numbers of Android Auto's focused displays in a reading. */
    fun focusedIds(displays: List<Display>): Set<Int> = displays.filter { it.focused && counts(it) }.map { it.id }.toSet()

    fun counts(display: Display) = PROJECTED in display.name && TRAMPOLINE !in display.name

    /** "GhAppLauncherService" out of "GhostActivityDisplay:ComponentInfo{…/….GhAppLauncherService}". */
    fun shortName(name: String): String = name.substringAfterLast('.').substringBefore('}')
}

/** The one watcher of the process, fed by the accessibility service and read by the projection. */
object AaScreens {
    /** How long a new screen must hold before it counts. */
    const val HOLD_MS = 400L

    /**
     * The hold the process's watcher uses: none, in every build (the rider's choice of 28/10/2026 after the ride of the fastest follow of
     * the screen: a flicker may show for a read). [HOLD_MS] is what a watcher made without its own holds.
     */
    const val HOLD_IN_USE_MS: Long = 0L

    val watch = AaScreenWatch(HOLD_IN_USE_MS) { com.reau.app.data.RideDiagnostics.log("aa-screen", it) }

    /**
     * Who reads Android Auto's screen again without a window event
     * ([AaScreenRereads]); the accessibility service while it is connected.
     */
    interface Rereader {
        /** An input was just sent to Android Auto. */
        fun inputSent()

        /** The projection (Android Auto's side) started or stopped. */
        fun projection(on: Boolean)
    }

    @Volatile var rereader: Rereader? = null

    /** Whether the projection runs now, for a re-reader that connects while it does. */
    @Volatile var projecting = false
        private set

    /** An input was sent to Android Auto (AaReceiver.sendInput). */
    fun inputSent() {
        com.reau.app.stream.InputLatency.keySent()
        com.reau.app.stream.InputBurst.sent(android.os.SystemClock.uptimeMillis())
        watch.inputSent(android.os.SystemClock.elapsedRealtime())
        rereader?.inputSent()
    }

    /** The joystick's select (KEY_ENTER) was sent to Android Auto (AaReceiver.sendInput); see [AaScreenWatch.selectSent]. */
    fun selectSent() {
        watch.selectSent(android.os.SystemClock.elapsedRealtime())
    }

    /**
     * A tap on the phone's screen reached Android Auto at ([x], [y]) of its video (AaReceiver.sendInput): a select when the menu is shown, and on the
     * side bar's shortcuts the same wait with an app shown ([AaScreenWatch.selectSent] with a rail slot).
     */
    fun tapSent(x: Int, y: Int, nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        AaRail.slot(x, y)?.let { watch.selectSent(nowMs, rail = it) }
        watch.selectSent(nowMs)
    }

    /** The Home key (KEY_HOME) was sent to Android Auto (AaReceiver.sendInput); see [AaScreenWatch.homeSent]. */
    fun homeSent() {
        watch.homeSent(android.os.SystemClock.elapsedRealtime())
    }

    /** Android Auto's side of the projection started or stopped (LiveSides). */
    fun projection(on: Boolean) {
        projecting = on
        // The next session starts from nothing: a screen left over from this one (the menu, say) must not shape its first moments.
        if (!on) watch.reset()
        rereader?.projection(on)
    }
}

/**
 * The screen shown, taken only once it held for [holdMs] (scrolling the menu or a window opening on top must not make the
 * fit blink); an unknown reading keeps the last screen. Each change is said once through [say], for the ride log.
 */
class AaScreenWatch(private val holdMs: Long, private val say: (String) -> Unit) {
    private val current = MutableStateFlow(AaScreen.UNKNOWN)
    val screen: StateFlow<AaScreen> = current.asStateFlow()

    // The short name of the display shown ("TemplateNavigationService", ...), after the same hold; it
    // also changes from one app to another, which [screen] does not.
    private val names = NameFollower(holdMs)
    val shownName: StateFlow<String?> = names.shown

    private var pending: AaScreen? = null
    private var since = 0L

    // When an input last went to Android Auto: a menu that takes the focus right after one is the rider's doing, not a flicker
    private val input = com.reau.app.stream.FreshInput(QUICK_WINDOW_MS)

    // The change waiting was first seen by a re-read (AaScreenRereads), not by a window event
    private var pendingByReread = false

    // The menu was left by a select ([selectSent]): the launcher's display keeps its focus, so it only counts again once it
    // takes the focus anew; and the displays focused then say nothing of what was picked ([SelectGate]).
    private val select = SelectGate()

    // When, after a select, no display having taken the focus anew means a navigation app (see [selectSent]); 0: not waiting
    private var navGuessAtMs = 0L
    private var focusedBefore = emptySet<Int>()
    private var shownId: Int? = null

    // What the picture says ([look]), while the screen rests on a select with no display saying what it was, or on the guess or the picture
    // after it. A display taking the focus anew, or the Home key, ends that.
    private val picture = PictureGate()

    // The menu was opened by the Home key ([homeSent]) and the displays do not say so: see [homeSent]
    private var menuByKey = false

    // What the last look of the picture was, whatever the gate: the media shortcut of the side bar goes by it ([selectSent])
    private var lastKind: PictureLook.Kind? = null

    // The navigation taken from the picture or the guess is held against a reading of the launcher's display while the picture is a map ([MapHold])
    private val mapHold = MapHold()

    // A display of Android Auto's was read in this session. With none after [BLIND_AFTER_MS] (24/10/2026: Android Auto 11.8 on Android 11 makes its
    // virtual displays private, and the accessibility service does not list their windows at all: the screen stayed unknown and Google Maps got the
    // apps' crop) the picture alone says the screen, as it does after a select: [blind]. A display read later ends that.
    private var sawDisplays = false
    private var firstLookMs = 0L
    private var blind = false

    /** Forgets the screen shown and everything waiting: the projection ended, the next session knows nothing yet. */
    @Synchronized
    fun reset() {
        pending = null
        names.reset()
        pendingByReread = false
        select.end()
        navGuessAtMs = 0L
        menuByKey = false
        picture.disarm()
        focusedBefore = emptySet()
        shownId = null
        lastKind = null
        sawDisplays = false
        firstLookMs = 0L
        blind = false
        mapHold.reset()
        current.value = AaScreen.UNKNOWN
    }

    /** A change was seen and waits for [holdMs]: read again then (the system says a window change only once). */
    val waiting: Boolean get() = pending != null || names.waiting

    /** An input (key, wheel step, touch) was sent to Android Auto at [nowMs] (the clock of [update]). */
    @Synchronized
    fun inputSent(nowMs: Long) {
        input.sent(nowMs)
    }

    /**
     * How long the change waiting must hold at [nowMs]: the menu coming up within [QUICK_WINDOW_MS] of an input (Home, say) is no
     * flicker, so it holds only [QUICK_HOLD_MS] (21/10/2026: key to fit took 1.0 s, 0.4 s of it this hold); anything else holds [holdMs].
     */
    @Synchronized
    fun holdNowMs(nowMs: Long): Long = if (pending == AaScreen.MENU && input.fresh(nowMs)) minOf(QUICK_HOLD_MS, holdMs) else holdMs

    /**
     * The joystick's select was sent with the menu shown: in the menu it always opens something (an app, Settings, Exit),
     * so the apps' screen is taken at once. Android Auto keeps the launcher's display focused after a select (the
     * menu's fit then stayed on Waze's map for minutes), so from then on a reading of the menu counts only when its
     * display takes the focus anew (it was not focused in the reading before), as it does when the menu really opens.
     * Nothing with any other screen shown.
     *
     * What was picked is not always said: Spotify, Phone and Waze usually take the focus of their displays, but Google Maps (its own
     * display, outside Android Auto's) and sometimes Waze take none (21/10 and 22/10/2026: the map in front for minutes with the
     * apps' crop). So [NAV_GUESS_MS] after the select, with no display having taken the focus anew, a navigation app is guessed (the
     * log says so); a display taking the focus later says the real one.
     *
     * [rail]: a tap on a shortcut of the side bar ([AaRail]) with an app shown (22/10/2026, the Spotify shortcut with Spotify in front: Android Auto went back to the
     * map, no display took the focus anew, and the apps' crop stayed 15 s on Google Maps' picture). The shortcut's app opens, and the one already in front sends
     * Android Auto back to the map, so the screen goes through the same wait; nothing in the menu or in the navigation. The navigation shortcut always
     * means the map. The media shortcut means it only when the last look was of a lit picture (Spotify's): Settings shares Spotify's display (the focus can not tell them
     * apart) and is dark, so with a dark or no look before it the media shortcut opens Spotify, and the screen stays the apps' (22/10/2026 again, a run of
     * scripts: the guess took Spotify for a map for 8 s).
     */
    @Synchronized
    fun selectSent(nowMs: Long = 0L, rail: AaRail.Slot? = null) {
        if (current.value != if (rail != null) AaScreen.APPS else AaScreen.MENU) return
        if (rail == AaRail.Slot.MEDIA && lastKind != PictureLook.Kind.MAP) return
        menuByKey = false
        mapHold.navFrom(byPicture = false)
        navGuessAtMs = if (nowMs > 0) nowMs + NAV_GUESS_MS else 0L
        select.start(focusedBefore)
        picture.arm(if (nowMs > 0) nowMs + PictureGate.SETTLE_MS else 0L)
        pending = null
        current.value = AaScreen.APPS
        say(if (rail != null) "aa-screen: a tap on the side bar's shortcuts, the displays say what it picked" else "aa-screen: apps (a select in the menu)")
    }

    /**
     * The Home key was sent: Android Auto opens its app menu. Its displays do not always say so: 5 of the 11 openings of 21/10/2026
     * (8 to 34 s with the apps' crop on the menu) had the launcher's display not focused, or focused beside the app's and the
     * trampoline's, as they were with the app in front. So the menu is taken at once, and stays until the select or a tap leaves it ([selectSent]),
     * until the launcher's own display alone says menu (the displays are the truth again from then on), or until another display takes the
     * focus anew (an app opened by something else). Nothing when the menu is already shown.
     */
    @Synchronized
    fun homeSent(nowMs: Long = 0L) {
        // The map stays on the screen for a moment after the key: looks of it are no news (25/10/2026, the Lite edition: the crop went back to the
        // navigation's for 1.5 s after each Home key, from two looks of the map that was still leaving).
        if (nowMs > 0) picture.holdMaps(nowMs + PictureGate.SETTLE_MS)
        if (current.value == AaScreen.MENU) return
        menuByKey = true
        mapHold.navFrom(byPicture = false)
        picture.disarm()
        select.end()
        navGuessAtMs = 0L
        pending = null
        current.value = AaScreen.MENU
        say("aa-screen: menu (the Home key)")
    }

    /**
     * A new reading at [nowMs]; the screen it changed to, or null when nothing changed. [reread]: the reading was not
     * caused by a window event, which the log line of the change says.
     */
    @Synchronized
    fun update(displays: List<AaScreenRule.Display>, nowMs: Long, reread: Boolean = false): AaScreen? {
        // Which display has just taken the focus, against the reading before
        val candidate = AaScreenRule.shown(displays, focusedBefore, shownId)
        if (displays.any(AaScreenRule::counts)) {
            sawDisplays = true
            if (blind) {
                blind = false
                picture.disarm()
            }
        }
        val wasFocused = focusedBefore
        focusedBefore = AaScreenRule.focusedIds(displays)
        if (candidate != null) shownId = candidate.id
        names.follow(candidate?.name?.let(AaScreenRule::shortName), nowMs)
        var seen = afterInputs(AaScreenRule.screenOf(candidate), candidate, wasFocused, focusedBefore)
        if (seen == AaScreen.MENU && mapHold.holds(current.value, nowMs)) {
            if (mapHold.firstHeld()) say("aa-screen: the launcher's display has the focus, but the picture is a map: still navigation")
            seen = AaScreen.UNKNOWN
        }
        // A reading that passed the waits says something: the displays decide from here, not a guess or the picture
        if (seen != AaScreen.UNKNOWN) picture.disarm()
        if (navGuessed(seen, nowMs)) return AaScreen.NAV
        return held(seen, nowMs, reread)?.also {
            mapHold.navFrom(byPicture = false)
            val by = if (pendingByReread) " by re-read" else ""
            say("aa-screen: ${it.name.lowercase()} (display ${candidate?.id}, ${candidate?.name?.let(AaScreenRule::shortName)}, by the focus)$by")
        }
    }

    /** The screen the reading [seen] holds at [nowMs] (the same one for the hold), or null while it waits or changes nothing. */
    private fun held(seen: AaScreen, nowMs: Long, reread: Boolean): AaScreen? {
        if (seen == AaScreen.UNKNOWN || seen == current.value) {
            pending = null
        } else if (pending != seen) {
            pending = seen
            since = nowMs
            pendingByReread = reread
        }
        val held = pending?.takeIf { nowMs - since >= holdNowMs(nowMs) } ?: return null
        pending = null
        current.value = held
        return held
    }

    /** The guess of [selectSent]: true when it just took effect (the screen is now the navigation's). */
    private fun navGuessed(seen: AaScreen, nowMs: Long): Boolean {
        if (navGuessAtMs == 0L) return false
        if (!select.active) {
            // a display took the focus anew: the displays say what it is
            navGuessAtMs = 0L
            return false
        }
        if (seen != AaScreen.UNKNOWN || nowMs < navGuessAtMs || current.value != AaScreen.APPS) return false
        navGuessAtMs = 0L
        pending = null
        current.value = AaScreen.NAV
        mapHold.navFrom(byPicture = true)
        picture.restart()
        say("aa-screen: nav (a guess: no display took the focus in ${NAV_GUESS_MS} ms after the select)")
        return true
    }

    /**
     * What a reading says once the two waits that an input started are applied: after a select ([select]) a reading of the very displays
     * that were focused at the select says nothing, and the launcher's display counts again only when it takes the focus anew; after the
     * Home key ([menuByKey]) the displays that were focused before say nothing new (the launcher alone is the truth again, and so is a
     * display focusing anew).
     */
    private fun afterInputs(reading: AaScreen, candidate: AaScreenRule.Display?, wasFocused: Set<Int>, focusedNow: Set<Int>): AaScreen {
        var seen = select.filter(reading, candidate, wasFocused, focusedNow)
        if (menuByKey && seen != AaScreen.UNKNOWN) {
            if (seen == AaScreen.MENU) menuByKey = false else if (candidate!!.id in wasFocused) seen = AaScreen.UNKNOWN else menuByKey = false
        }
        return seen
    }

    /**
     * What Android Auto's picture looks like at [nowMs] ([PictureLook]); the screen it changed to, or null. It only counts where the
     * displays said nothing ([PictureGate]); each change, and the first look that agrees with the screen, is said with what it saw.
     * With no display of Android Auto's read at all (the edition without the accessibility service, or a version whose screens it cannot list: [blind]) the picture
     * says the menu and the apps as well as the map, and may leave the menu; with displays it only tells a map from what is not one.
     */
    @Synchronized
    fun look(kind: PictureLook.Kind, nowMs: Long): AaScreen? {
        lastKind = kind
        mapHold.look(kind, nowMs)
        if (firstLookMs == 0L) firstLookMs = nowMs
        if (!sawDisplays && !blind && nowMs - firstLookMs >= BLIND_AFTER_MS) {
            blind = true
            picture.arm(0L)
            say("aa-screen: no display of Android Auto's read in ${BLIND_AFTER_MS} ms, the picture says the screen")
        }
        val shown = current.value
        val verdict = if (!blind && (shown == AaScreen.MENU || shown == AaScreen.UNKNOWN)) null else picture.next(kind, nowMs, shown, blind)
        val to = verdict?.changeTo
        if (verdict != null && to == null) say("aa-screen: ${shown.name.lowercase()} confirmed by the picture (${verdict.saw})")
        if (verdict != null && to != null) {
            pending = null
            select.end()
            navGuessAtMs = 0L
            current.value = to
            mapHold.navFrom(byPicture = to == AaScreen.NAV)
            say("aa-screen: ${to.name.lowercase()} (by the picture: ${verdict.saw}, from ${shown.name.lowercase()})")
        }
        return to
    }

    private companion object {
        const val QUICK_HOLD_MS = 100L
        const val NAV_GUESS_MS = 800L
        const val BLIND_AFTER_MS = 3_000L
        const val QUICK_WINDOW_MS = 2_000L
    }
}

/**
 * Whether a reading of the launcher's display as the menu is to be ignored: the navigation was taken from the picture (or the guess) after a
 * select, the picture has been a map within [MAP_FRESH_MS] and no look of the menu or an app came after. Android Auto keeps the launcher's
 * display focused after a select, and the reading just after one, with nothing focused for a moment in between, takes it for the menu opening
 * again (26/10/2026, four times in a row: Waze picked in the menu, the map in front, and the apps' crop on it for 45 s). The picture is the
 * truth then: when it stops being a map the reading counts, and so does the Home key at once.
 */
internal class MapHold {
    private var byPicture = false
    private var mapAt: Long? = null
    private var said = false

    /** The navigation was taken [byPicture] (or the guess), or the screen came from anywhere else. */
    fun navFrom(byPicture: Boolean) {
        this.byPicture = byPicture
        said = false
    }

    /** A look of [kind] at [nowMs]: a map is remembered, the menu and the apps forget it, an unsure one changes nothing. */
    fun look(kind: PictureLook.Kind, nowMs: Long) {
        if (kind == PictureLook.Kind.MAP) mapAt = nowMs else if (kind != PictureLook.Kind.UNSURE) mapAt = null
    }

    fun reset() {
        byPicture = false
        mapAt = null
        said = false
    }

    /** Whether the menu is not believed at [nowMs] with [shown] on the screen. */
    fun holds(shown: AaScreen, nowMs: Long): Boolean {
        val at = mapAt
        return byPicture && shown == AaScreen.NAV && at != null && nowMs - at in 0..MAP_FRESH_MS
    }

    /** True the first time it is asked after the hold started, so the log says it once. */
    fun firstHeld(): Boolean = !said.also { said = true }

    private companion object {
        /** A look of a map this recent still says a map is in front (the looks come every 250 ms; a few may be unsure in between). */
        const val MAP_FRESH_MS = 3_000L
    }
}

/**
 * What a select in the menu leaves waiting ([AaScreenWatch.selectSent]): while [active], the readings that say nothing of what was picked
 * are dropped ([filter]). Those are the ones of exactly the displays that were focused at the select (22/10/2026: Waze's display and an
 * earlier app's template display focused together, the Waze picked in the menu read as the apps for two minutes), and the launcher's own
 * display kept in front or focused again before it took the focus anew.
 */
internal class SelectGate {
    var active = false
        private set
    private var focusedAtSelect = emptySet<Int>()
    private var changed = false

    fun start(focused: Set<Int>) {
        active = true
        focusedAtSelect = focused
        changed = false
    }

    fun end() {
        active = false
    }

    /** The reading [reading] of [candidate] after the gate: what says nothing is UNKNOWN, and anything else ends the wait. */
    fun filter(reading: AaScreen, candidate: AaScreenRule.Display?, wasFocused: Set<Int>, focusedNow: Set<Int>): AaScreen {
        if (!active) return reading
        if (focusedNow != focusedAtSelect) changed = true
        val nothingNew = !changed && focusedNow == focusedAtSelect
        // The launcher's window may stay the active one for a moment after the select: it is the menu only once it has the focus anew
        val staleMenu = reading == AaScreen.MENU && (candidate!!.id in wasFocused || !candidate.focused)
        val says = reading != AaScreen.UNKNOWN && !staleMenu && !nothingNew
        if (says) active = false
        return if (reading == AaScreen.UNKNOWN || says) reading else AaScreen.UNKNOWN
    }
}

/**
 * What the picture may do to the screen ([AaScreenWatch.look]). Where displays are read, only while [armed] (a select left the screen to a guess, or to the
 * picture itself): two looks of a map in a row make the screen the navigation's, and three that are not a map (the menu or an app) take the navigation's off. The
 * first moments after the select still show the menu ([SETTLE_MS]), and each reading of the displays that says something disarms it. With no display read at
 * all ([AaScreenWatch.look]'s blind mode) it is always listening and says the three screens: two looks of a map, or three of the menu, or three of an app.
 */
internal class PictureGate {
    /** What a look made of the screen: [changeTo] it, or null when it only confirms the screen ([saw] says what the picture was). */
    class Verdict(val changeTo: AaScreen?, val saw: String)

    private var armed = false
    private var fromMs = 0L

    // Looks of a map before this time do not count: it is the one that was in front when the Home key was sent, still leaving.
    private var mapsFromMs = 0L
    private var maps = 0
    private var menus = 0
    private var apps = 0
    private var others = 0
    private var confirmed = false

    fun arm(fromMs: Long) {
        armed = true
        this.fromMs = fromMs
        restart()
    }

    fun disarm() {
        armed = false
        restart()
    }

    /** The Home key was sent: looks of a map before [untilMs] are the old screen's, not a new map. */
    fun holdMaps(untilMs: Long) {
        mapsFromMs = untilMs
        restart()
    }

    /** The looks counted start over (the guess was just made, or a look changed the screen). */
    fun restart() {
        maps = 0
        menus = 0
        apps = 0
        others = 0
        confirmed = false
    }

    /**
     * What the look [kind] at [nowMs] does to the [shown] screen. [blind]: no display of Android Auto's is read, so the picture needs no arming (a select or the Home
     * key leaves it listening) and says the menu and the apps too; otherwise it only tells a map from what is not one (the menu and the apps count together).
     */
    fun next(kind: PictureLook.Kind, nowMs: Long, shown: AaScreen, blind: Boolean = false): Verdict? {
        if (!listening(kind, nowMs, blind)) return null
        when (kind) {
            PictureLook.Kind.MAP -> { maps++; menus = 0; apps = 0; others = 0 }
            PictureLook.Kind.MENU -> { menus++; others++; maps = 0; apps = 0 }
            PictureLook.Kind.APPS -> { apps++; others++; maps = 0; menus = 0 }
            PictureLook.Kind.UNSURE -> restart()
        }
        return if (blind) blindVerdict(shown) else armedVerdict(shown)
    }

    /**
     * Whether a look of [kind] at [nowMs] counts. After a select the first moments still show the menu, which no look takes for a map: only the looks
     * that are not of a map wait for the settle, so the map of the app picked comes in as soon as it is drawn (it took 0.8 s more, 25/10/2026).
     * After the Home key it is the other way round: the map leaving the screen is the old one ([holdMaps]).
     */
    private fun listening(kind: PictureLook.Kind, nowMs: Long, blind: Boolean): Boolean = when {
        !armed && !blind -> false
        kind == PictureLook.Kind.MAP -> nowMs >= mapsFromMs
        else -> nowMs >= fromMs
    }

    private fun armedVerdict(shown: AaScreen): Verdict? {
        val mapNow = maps >= MAP_LOOKS
        val darkNow = others >= OTHER_LOOKS
        return when {
            mapNow && shown != AaScreen.NAV -> changed(AaScreen.NAV, "a map")
            darkNow && shown == AaScreen.NAV -> changed(AaScreen.APPS, "not a map")
            confirmed -> null
            mapNow && shown == AaScreen.NAV -> confirm("a map")
            darkNow && shown == AaScreen.APPS -> confirm("not a map")
            else -> null
        }
    }

    private fun blindVerdict(shown: AaScreen): Verdict? {
        val mapNow = maps >= MAP_LOOKS
        val menuNow = menus >= OTHER_LOOKS
        val appsNow = apps >= OTHER_LOOKS
        return when {
            mapNow && shown != AaScreen.NAV -> changed(AaScreen.NAV, "a map")
            menuNow && shown != AaScreen.MENU -> changed(AaScreen.MENU, "the app menu")
            appsNow && shown != AaScreen.APPS -> changed(AaScreen.APPS, "an app")
            confirmed -> null
            mapNow -> confirm("a map")
            menuNow -> confirm("the app menu")
            appsNow -> confirm("an app")
            else -> null
        }
    }

    private fun changed(to: AaScreen, saw: String): Verdict {
        restart()
        return Verdict(to, saw)
    }

    private fun confirm(saw: String): Verdict {
        confirmed = true
        return Verdict(null, saw)
    }

    companion object {
        const val SETTLE_MS = 800L
        const val MAP_LOOKS = 2
        const val OTHER_LOOKS = 3
    }
}

/** The short name of the display shown, after the same hold as the screen; it also changes from one app to another, which the screen does not. */
internal class NameFollower(private val holdMs: Long) {
    private val current = MutableStateFlow<String?>(null)
    val shown: StateFlow<String?> = current.asStateFlow()
    private var pending: String? = null
    private var since = 0L

    val waiting: Boolean get() = pending != null

    fun reset() {
        pending = null
        current.value = null
    }

    /** [name] shown at [nowMs] becomes [shown] once it held; none of Android Auto's keeps the name. */
    fun follow(name: String?, nowMs: Long) {
        if (name == null || name == current.value) {
            pending = null
        } else if (pending != name) {
            pending = name
            since = nowMs
        }
        if (pending != null && nowMs - since >= holdMs) {
            current.value = pending
            pending = null
        }
    }
}
