package com.reau.app.ui

import com.reau.app.video.FrameFit

/**
 * The dash's mask over the "Tela do Android Auto": [on] as saved, the settings' fit [mapping] of Android Auto's
 * [sourceW]×[sourceH] frame, and [onChange] for the switch.
 */
class AaScreenMask(
    val on: Boolean,
    val mapping: FrameFit.Mapping,
    val sourceW: Int,
    val sourceH: Int,
    /** The area the fit is made for and the dash's video is composed in ([REauSettings.frameSize]). */
    val area: com.reau.app.dash.DashConfig.FrameSize = com.reau.app.dash.DashConfig.FULL_FRAME,
    val onChange: (Boolean) -> Unit,
)
