package com.reau.app.video

/**
 * What Android Auto's picture looks like, from the tiny copy of it the compositor takes ([SAMPLE_W]×[SAMPLE_H] RGBA, rows from the top): the map
 * (Waze, Google Maps, day or night), the app menu, another app (Spotify, the phone, Settings...), or none of them for sure. The answer comes from
 * [ScreenNet], a hand-written little neural net; before 24/10/2026 it came from the share of lit pixels, which could say "a map" or "not a map" and nothing
 * more (the app menu and the apps were all "dark", and a day-theme Spotify read as a map).
 *
 * Measured on 24/10/2026 on 5830 frames of 35 recorded sessions (owner's phone, Android Auto 17.x, and the test phone, Android Auto 11.8), every label checked
 * by eye: 99.98% right, none of the 4926 maps read as an app; the brightness rule it replaces reached 99% in only 13
 * of 30 sessions and could not tell the menu from the apps. Across sessions (the net never saw the session it was tested on) the study found 30 of 30 sessions at
 * 99% for map against not-map and 17 of 17 for menu against apps (the study's report; the figures are a little optimistic, the author saw some of
 * them before fixing the design). It does NOT carry over to a phone it has seen no frame of (44% from the owner's phone to the test phone, the menu being black
 * on one and gray on the other): both are in its training set.
 *
 * Retrained on 25/10/2026 with 648 more frames of the Lite edition on the owner's phone (a ride of Google Maps with the dark map and of Waze by day, the app
 * menu and Spotify, every one looked at by eye). Read by the net before it had them: 13951 of 14109 frames of those sessions were a map, none with a confidence under
 * 0.988, and none of the rest was a map read as an app or as the menu. Across sessions with all the data (4 groups of sessions, never the group it was tested on):
 * 5620 of 5621 maps and 574 of 574 menus right, no map read as an app and no app as a map. The abstention limit stayed at 0.9: the
 * study's own optimum for the new data was 0.7, which let one map be read as the menu.
 *
 * Retrained on 27/10/2026 with 628 more frames from the 60 recordings it had not used (25/10 night to 27/10, both editions, the owner's phone: Waze guiding a
 * route by day and by night, Google Maps with a call card, the app menu, Spotify), every one looked at by eye. Read by the net before it had them: of 70347
 * samples (one every 250 ms) none of the 628 looked at was wrong but 10 empty loading screens (left out); where the log and the net disagreed (226 samples
 * of a map with the launcher's display in focus, 64 of the menu after a select in it) the net was right. Across sessions on the owner's phone (5 groups, 91 sessions, 5 seeds):
 * 99.3 to 100% of the apps against 97.1% for the same protocol on the 25/10 data, every session at 99% in 4 seeds of 5. On a phone it never saw it is still
 * 49%. The limit stayed at 0.9: the study's optimum was 0.95, which would leave 108 of the new maps unsure.
 *
 * The net was trained on pictures reduced by the AVERAGE of each block; the GPU's bilinear fetch reads only 2×2 pixels of each 12×12 block and made the same net
 * 82.6% right with 21 maps read as apps, so the compositor reduces the copy with a 4×4-tap box ([CompositorGl.look], 99.96%) and hands it over top row first
 * ([topDown]: `glReadPixels` returns the bottom row first and turned the net's answer to 72%).
 *
 * Still, a picture classifier can be wrong on a screen it never saw (a full-screen album cover by day, a card laid over the map): the screen only takes its
 * answer where the displays said nothing ([com.reau.app.aa.AaScreenWatch]).
 */
object PictureLook {
    /** MAP: a navigation map. MENU: the app launcher. APPS: any other app. UNSURE: black, loading, a fade, or the net is not sure enough. */
    enum class Kind { MAP, MENU, APPS, UNSURE }

    /** The size of the tiny copy the compositor takes of the picture. */
    const val SAMPLE_W = ScreenNet.SAMPLE_W
    const val SAMPLE_H = ScreenNet.SAMPLE_H

    /** What [rgba] ([width]×[height] RGBA, rows from the top) shows. A picture of another size is [Kind.UNSURE]. */
    fun of(rgba: ByteArray, width: Int, height: Int): Kind = when (ScreenNet.classify(rgba, width, height).screen) {
        ScreenNet.Screen.NAV -> Kind.MAP
        ScreenNet.Screen.MENU -> Kind.MENU
        ScreenNet.Screen.APPS -> Kind.APPS
        ScreenNet.Screen.UNSURE -> Kind.UNSURE
    }

    /** [bottomUp] ([width]×[height] RGBA as `glReadPixels` returns it: the last row of the picture first) with its rows in the reverse order, in a new array. */
    fun topDown(bottomUp: ByteArray, width: Int, height: Int): ByteArray {
        val stride = width * BYTES_PER_PIXEL
        require(bottomUp.size == stride * height) { "not a ${width}x$height RGBA picture: ${bottomUp.size} bytes" }
        val out = ByteArray(bottomUp.size)
        for (row in 0 until height) System.arraycopy(bottomUp, row * stride, out, (height - 1 - row) * stride, stride)
        return out
    }

    private const val BYTES_PER_PIXEL = 4
}
