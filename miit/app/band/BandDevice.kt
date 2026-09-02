package com.miit.app.band

/** Runtime updates received from the connected Xiaomi band. */
data class BandDataUpdate(
    val batteryPercentage: Int? = null,
    val batteryState: Int? = null,
    val charging: Boolean? = null,
    val firmware: String? = null,
    val model: String? = null,
    val hardware: String? = null,
    val serialNumber: String? = null,
    val displays: List<BandDisplay>? = null,
    val heartRate: Int? = null
)

/** Information discovered from a Mi Band or compatible Xiaomi Smart Band. */
data class BandDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val model: String? = null,
    val firmware: String? = null,
    val manufacturer: String? = null,
    val batteryPercentage: Int? = null,
    val batteryState: Int? = null,
    val charging: Boolean? = null,
    val countryVariant: String? = null,
    val hardware: String? = null,
    val serialNumber: String? = null,
    val heartRate: Int? = null,
    val displays: List<BandDisplay> = emptyList(),
    val connected: Boolean = false,
    val authenticated: Boolean = false
)

enum class BandConnectionState {
    Idle,
    Scanning,
    Connecting,
    Connected,
    AwaitingXiaomiBinding,
    Authenticating,
    Authenticated,
    Disconnected,
    Error
}
