package com.miit.app.band

/** Information discovered from a Mi Band or compatible Xiaomi Smart Band. */
data class BandDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val model: String? = null,
    val firmware: String? = null,
    val manufacturer: String? = null,
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
