package com.miit.app.core

/** Device-neutral information detected from a wearable. */
data class BandInfo(
    val model: String,
    val region: String?,
    val firmware: String?,
    val hardware: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val colorDepth: Int? = null
)

data class WatchfaceProject(
    val id: String,
    val name: String,
    val target: BandInfo?,
    val elements: List<WatchfaceElement> = emptyList()
)

sealed interface WatchfaceElement {
    data class Text(val value: String, val x: Float, val y: Float, val size: Float) : WatchfaceElement
    data class Image(val path: String, val x: Float, val y: Float, val width: Float, val height: Float) : WatchfaceElement
    data class Shape(val kind: String, val x: Float, val y: Float, val width: Float, val height: Float) : WatchfaceElement
}

interface BandTransport {
    suspend fun scanAndConnect(): Result<BandInfo>
    suspend fun readCurrentWatchface(): Result<ByteArray>
    suspend fun installWatchface(payload: ByteArray): Result<Unit>
    suspend fun disconnect()
}

interface WatchfaceCompiler {
    fun canCompile(target: BandInfo): Boolean
    fun compile(project: WatchfaceProject): Result<ByteArray>
}
