package com.reau.app.aa

/**
 * The joystick page: every control of the dash's
 * joystick, in the media mode and in the map mode, once and held, with the action the rider picks. Only what the rider
 * changed is kept ([encode]); a slot nothing was chosen for takes [defaultOf]: in the media mode, what one tap did before
 * the page (the wheel, home, confirm); in the map mode, the sides move the focus, down is the info ring's switch and the rest does nothing. That is not the
 * page the app starts with: that one is [com.reau.app.settings.REauSettings.DEFAULT_JOYSTICK] (the app menu and Gemini in the media
 * mode, zoom out and zoom in on the map mode's sides, the info ring below them), which a new install starts with. The calls act above the page
 * ([JoystickMap]).
 *
 * No "twice": a double tap does not work well on the dash's joystick; a "twice" choice saved before
 * is left out when read. "Held" is kept but never fires: as far as is known the dash sends one code per press,
 * with no release.
 */
data class JoystickBindings(private val chosen: Map<Slot, Action> = emptyMap()) {
    /** The dash's two modes, told by the codes it sends: track and volume in the media mode, zoom in the map mode. */
    enum class Mode { MEDIA, MAP }

    enum class Control { LEFT, RIGHT, UP, DOWN, CENTER }

    enum class Gesture { ONCE, HOLD }

    /**
     * What a control can do: keys and wheel steps for Android Auto, or the phone's call. [command] is what Android
     * Auto (or the phone) gets; the assistant only where Gemini's microphone was given.
     */
    enum class Action(private val make: (assistant: Boolean) -> AaCommand?) {
        NOTHING({ null }),
        WHEEL_PREVIOUS({ AaCommand.Scroll(-1) }),
        WHEEL_NEXT({ AaCommand.Scroll(1) }),
        CONFIRM({ AaCommand.Key(ServiceDiscoveryResponse.KEY_ENTER) }),
        BACK({ AaCommand.Key(ServiceDiscoveryResponse.KEY_BACK) }),
        HOME({ AaCommand.Key(ServiceDiscoveryResponse.KEY_HOME) }),
        PLAY_PAUSE({ AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_PLAY_PAUSE) }),
        NEXT_SONG({ AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_NEXT) }),
        PREVIOUS_SONG({ AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_PREVIOUS) }),
        ASSISTANT({ assistant -> if (assistant) AaCommand.Key(ServiceDiscoveryResponse.KEY_ASSISTANT) else null }),
        ZOOM_IN({ AaCommand.PanelZoom(1) }),
        ZOOM_OUT({ AaCommand.PanelZoom(-1) }),

        /**
         * The info ring's own switch: it sends Android Auto nothing, the dash's down button in its map mode (0x15) shows and hides REau's
         * ring on the video ([com.reau.app.dash.RingCycle]). Only that button sends that code, so only that slot can have it ([allowedAt]).
         */
        INFO_RING({ null }),
        ;

        fun command(assistant: Boolean): AaCommand? = make(assistant)

        /** Whether [slot] can have this action: all of them anywhere, but the info ring's switch only on the map mode's down once. */
        fun allowedAt(slot: Slot): Boolean = this != INFO_RING || slot == INFO_RING_SLOT
    }

    data class Slot(val mode: Mode, val control: Control, val gesture: Gesture) {
        /** As written in the settings and the ride log: `media.right.twice`. */
        val key: String get() = "${mode.name.lowercase()}.${control.name.lowercase()}.${gesture.name.lowercase()}"
    }

    operator fun get(slot: Slot): Action = if (isLocked(slot)) Action.NOTHING else chosen[slot]?.takeIf { it.allowedAt(slot) } ?: defaultOf(slot)

    /** This page with [slot] set to [action]; set back to its default, or locked, it is no longer kept as a choice. */
    fun with(slot: Slot, action: Action): JoystickBindings = when {
        isLocked(slot) || !action.allowedAt(slot) -> JoystickBindings(chosen - slot)
        action == defaultOf(slot) -> JoystickBindings(chosen - slot)
        else -> JoystickBindings(chosen + (slot to action))
    }

    /**
     * Whether the map mode has both zoom actions ([Action.ZOOM_IN] and [Action.ZOOM_OUT]), each on some control (once). Without them
     * the dash is not told its zoom state, so its own "ZOOM" is not shown ([com.reau.app.dash.DashSession.zoomLabel]).
     */
    val zoomControls: Boolean
        get() = ZOOM_ACTIONS.all { wanted -> SLOTS.any { it.mode == Mode.MAP && it.gesture == Gesture.ONCE && this[it] == wanted } }

    /**
     * Whether the media mode has a music control on some control (once): the previous song, play/pause or the next song. Without one the
     * dash is not sent the music card ([com.reau.app.dash.DashSession.musicCardAllowed]): a card to look at and no button for the song.
     */
    val mediaControls: Boolean
        get() = SLOTS.any { it.mode == Mode.MEDIA && it.gesture == Gesture.ONCE && this[it] in MEDIA_ACTIONS }

    /**
     * Whether the dash's down button in its map mode switches the info ring: its action is the ring's ([Action.INFO_RING], the default). With
     * another action there the rider has taken the button for it, and the press must not show or hide the ring as well
     * ([com.reau.app.dash.DashSession.infoRingPress]).
     */
    val infoRingControl: Boolean
        get() = this[INFO_RING_SLOT] == Action.INFO_RING

    /** The choices only, in the page's order: `media.center.once=HOME;map.right.once=NEXT_SONG`. */
    fun encode(): String = SLOTS.filter { it in chosen }.joinToString(";") { "${it.key}=${chosen.getValue(it).name}" }

    companion object {
        /** Ten in each mode: five controls, once and held. */
        val SLOTS: List<Slot> = Mode.entries.flatMap { mode ->
            Control.entries.flatMap { control -> Gesture.entries.map { Slot(mode, control, it) } }
        }

        val DEFAULT = JoystickBindings()

        /** The one slot the info ring's switch can be on: the dash's down button in its map mode, one tap ([com.reau.app.dash.RingCycle.RING_PRESS]). */
        val INFO_RING_SLOT = Slot(Mode.MAP, Control.DOWN, Gesture.ONCE)

        private val ZOOM_ACTIONS = listOf(Action.ZOOM_IN, Action.ZOOM_OUT)

        private val MEDIA_ACTIONS = setOf(Action.PREVIOUS_SONG, Action.PLAY_PAUSE, Action.NEXT_SONG)


        private val DEFAULTS = mapOf(
            Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE) to Action.WHEEL_PREVIOUS,
            Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE) to Action.WHEEL_NEXT,
            Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE) to Action.HOME,
            Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE) to Action.CONFIRM,
            // In the dash's map mode only the sides move the focus.
            Slot(Mode.MAP, Control.LEFT, Gesture.ONCE) to Action.WHEEL_PREVIOUS,
            Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE) to Action.WHEEL_NEXT,
            // Down is REau's: the info ring's switch (the rider can give the button another action).
            INFO_RING_SLOT to Action.INFO_RING,
        )

        fun defaultOf(slot: Slot): Action = DEFAULTS[slot] ?: Action.NOTHING

        /**
         * The controls kept in its map mode: up is the dash's own back and the centre puts it in the media mode. They take no action, whatever
         * was saved for them. Down is REau's by default, for switching the info ring ([com.reau.app.dash.RingCycle.RING_PRESS]), and can be
         * given another action.
         */
        val LOCKED: Set<Pair<Mode, Control>> = setOf(Mode.MAP to Control.UP, Mode.MAP to Control.CENTER)

        fun isLocked(slot: Slot): Boolean = slot.mode to slot.control in LOCKED

        private val BY_KEY = SLOTS.associateBy { it.key }

        /**
         * [text] without its map-mode choices: those saved
         * before the one-tap joystick put Gemini on every map line, and the map mode's sides move the focus now.
         */
        fun withoutMapChoices(text: String): String = text.split(';').filter { it.isNotBlank() && !it.startsWith("map.") }.joinToString(";")

        /** The choices from [encode]'s text; what cannot be read is left out, the rest is kept. */
        fun decode(text: String): JoystickBindings {
            val chosen = text.split(';').mapNotNull { entry ->
                val (key, name) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val slot = BY_KEY[key] ?: return@mapNotNull null
                val action = Action.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                slot to action
            }
            return chosen.fold(DEFAULT) { page, (slot, action) -> page.with(slot, action) }
        }
    }
}
