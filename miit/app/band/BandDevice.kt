package com.miit.app.band

/** A display entry reported by the connected Xiaomi band. */
data class BandDisplay(
    val code: String,
    val name: String,
    val disabled: Boolean = false,
    val isSettings: Int = 0,
    val inMoreSection: Boolean = false
)

/** Runtime updates received from the connected Xiaomi band. */
data class BandDataUpdate(
    val batteryPercentage: Int? = null,
    val firmware: String? = null,
    val model: String? = null,
    val displays: List<BandDisplay>? = null
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
    val countryVariant: String? = null,
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
