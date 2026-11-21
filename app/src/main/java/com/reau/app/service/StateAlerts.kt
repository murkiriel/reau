package com.reau.app.service

/**
 * What a change of the projection's state asks of the notices to the rider, as a pure rule: the notice of the phone's Wi-Fi being off comes when the
 * state raises [Projection.wifiOffAlarm] and goes when it falls, and the dash being back is told (by the voice, if the rider asked for it) when a
 * running projection's dash turns ready.
 */
object StateAlerts {
    enum class Alert { WIFI_OFF_SHOW, WIFI_OFF_CLEAR, DASH_BACK }

    fun between(before: Projection, after: Projection): List<Alert> = listOfNotNull(
        Alert.WIFI_OFF_SHOW.takeIf { !before.wifiOffAlarm && after.wifiOffAlarm },
        Alert.WIFI_OFF_CLEAR.takeIf { before.wifiOffAlarm && !after.wifiOffAlarm },
        Alert.DASH_BACK.takeIf { after.phase == Phase.RUNNING && before.dash != DashLink.READY && after.dash == DashLink.READY },
    )
}
