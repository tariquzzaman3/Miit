package com.miit.app.band

data class BandDisplay(
    val code: String?,
    val name: String?,
    val disabled: Boolean = false,
    val inMoreSection: Boolean = false
)
