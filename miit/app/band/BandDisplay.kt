package com.miit.app.band

/** A display item or watchface reported by the Xiaomi band. */
data class BandDisplay(
    val code: String?,
    val name: String?,
    val disabled: Boolean = false,
    val inMoreSection: Boolean = false,
    val active: Boolean = false,
    val canDelete: Boolean = false,
    val source: Source = Source.DISPLAY_ITEM,
    val previewPath: String? = null
) {
    enum class Source {
        DISPLAY_ITEM,
        WATCHFACE
    }

    val stableId: String
        get() = listOf(source.name, code.orEmpty(), name.orEmpty()).joinToString(":")
}
