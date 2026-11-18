package com.reau.app.aa

/**
 * The log line for Android Auto's service discovery request. The request carries the name the owner gave the phone, which the shared log
 * must not: the line says only how long the name is (the log is shared with whoever helps with a problem).
 */
internal object DiscoveryLog {
    fun line(phoneName: String): String = "Service Discovery Request (the phone's name, ${phoneName.length} characters, is not logged)"

    /**
     * What the response says about the vehicle, written only when the experiment "Android Auto in motorcycle mode" is on (null otherwise: the log of
     * a normal ride is as it was). A ride log that has it can be told apart from the rides announced as a car, and says the vehicle id Android Auto
     * will remember the type under.
     */
    fun announcedLine(config: AaConfig): String? = if (!config.motorcycle) null else
        "Service Discovery Response: head unit announced as a MOTORCYCLE (vehicle_type ${ServiceDiscoveryResponse.VEHICLE_TYPE_MOTORCYCLE}, " +
            "vehicle_id ${ServiceDiscoveryResponse.vehicleIdOf(config)}) - experiment"
}
