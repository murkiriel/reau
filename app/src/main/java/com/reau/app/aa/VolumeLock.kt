package com.reau.app.aa

/**
 * The joystick's volume lock. The handlebar joystick is not precise, so after a tap of one of its volume actions
 * ([com.reau.app.aa.JoystickBindings.Action.VOLUME_UP], [com.reau.app.aa.JoystickBindings.Action.VOLUME_DOWN]) the joystick takes nothing but those two for
 * [VOLUME_LOCK_MS]: any other tap is dropped by [JoystickMap] (nothing goes to Android Auto or the phone, no echo, no legend) and does not renew the lock; each
 * volume tap renews it. This is the one clock of the lock, and of the end of the volume card too: a card from the joystick is shown until [untilMs], so the card
 * and the lock end at the same instant ([com.reau.app.dash.VolumeCards.showMs]).
 *
 * Only with the volume notice on ([enabled], set from the settings): off, there is no lock and the joystick is what it always was. A call ringing or going on
 * breaks the lock (the call's keys must always work; [JoystickMap] does it) and no volume tap locks while the phone is in a call. The dash changes its own mode by
 * itself (the map mode's centre puts it in its media mode): REau does not stop that, it only sends nothing for it.
 *
 * The times are the caller's (the monotonic clock in the app, a number in a test).
 */
class VolumeLock {
    /** Whether the lock exists (the volume notice's switch). Turning it off takes the lock away at once. */
    @Volatile var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) clear()
        }

    @Volatile private var until = NOT_LOCKED

    /** Locks for [VOLUME_LOCK_MS] from [nowMs] (a volume tap, again: the time starts over); nothing when [enabled] is off. */
    fun renew(nowMs: Long) {
        if (enabled) until = nowMs + VOLUME_LOCK_MS
    }

    /** How long the lock still has at [nowMs]; 0 when it is over, never started or off. */
    fun leftMs(nowMs: Long): Long = if (enabled) (until - nowMs).coerceAtLeast(0L) else 0L

    /** The instant the lock ends, on the caller's clock; meaningful while [leftMs] is above 0. */
    val untilMs: Long get() = until

    /** Takes the lock away now (a call came, or the switch went off). */
    fun clear() {
        until = NOT_LOCKED
    }

    companion object {
        /** How long the joystick takes only the volume actions after one of them, and how long a card from the joystick stays: the single place of the number. */
        const val VOLUME_LOCK_MS = 5_000L

        private const val NOT_LOCKED = Long.MIN_VALUE / 2
    }
}
