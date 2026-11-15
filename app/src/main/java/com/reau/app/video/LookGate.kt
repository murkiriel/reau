package com.reau.app.video

/**
 * Whether the compositor hands its looks of the picture on ([DashCompositor.onLook]). The compositor outlives Android Auto's sessions (it repeats
 * the last picture to the dash while Android Auto is away), so between one projection and the next it goes on looking at the old, standing picture.
 * Those looks are not the new projection's: one of them marking the start of the screen watch's first moments
 * ([com.reau.app.aa.AaScreenWatch]) made the next projection's 3 s shrink to 0 to 1.3 s (8 of 23 projections of 12 to 14/11/2026). So a new
 * projection holds the gate shut ([awaitPicture]) and the first picture latched after that opens it ([pictureArrived]): the first look of a
 * projection is the first look of its own first picture. Open by default, so a compositor nobody holds behaves as it always did.
 * Touched on the compositor's thread only.
 */
internal class LookGate {
    private var waiting = false

    /** Whether a look may be handed on. */
    val open: Boolean get() = !waiting

    /** A new projection is starting: no look until a picture of its own arrives. */
    fun awaitPicture() {
        waiting = true
    }

    /** A picture was latched: it is the new projection's if the gate was held, and the looks go on. */
    fun pictureArrived() {
        waiting = false
    }
}
