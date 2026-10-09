package com.miit.app

import android.content.Context
import android.os.BatteryManager
import com.miit.app.band.BandDevice
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Safety boundary for MIIT's reverse-engineered Xiaomi face container.
 * A passing result means only that the package matches MIIT's own exact
 * compiler layout; it is not a guarantee for an untested firmware revision.
 */
object MiitWatchfaceSafety {
    /**
     * MIIT's current reverse-engineered native container format identifier.
     * This label is not an official Xiaomi specification name.
     */
    const val NATIVE_FORMAT_LABEL = "Xiaomi Smart Band .face"
    // Current compiler writes the title to a 60-byte region; reserve a terminating NUL byte.
    const val MAX_WATCHFACE_NAME_UTF8_BYTES = 59
    const val MIN_BAND_BATTERY_PERCENT = 50
    const val MIN_PHONE_BATTERY_PERCENT = 30
    const val MAX_PACKAGE_BYTES = 4 * 1024 * 1024

    private const val MAIN_HEADER_SIZE = 0xA8
    private const val FACE_HEADER_SIZE = 0x58
    private const val DEFINITION_SIZE = 16
    private const val ELEMENT_PROPERTY_SIZE = 16
    private const val IMAGE_HEADER_SIZE = 12

    data class PackageReport(
        val safe: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val width: Int?,
        val height: Int?,
        val faceCount: Int?,
        val sizeBytes: Int,
        val sha256: String
    ) {
        val sizeLabel: String
            get() = String.format(Locale.US, "%.2f MB", sizeBytes / 1_048_576f)
    }

    data class PreflightReport(
        val safe: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    /**
     * A display profile is not automatically a safe direct-install target.
     * Only profiles using MIIT's current standard Band 9/10 .face path are enabled.
     */
    data class SupportedTarget(
        val id: String,
        val familyId: String,
        val width: Int,
        val height: Int,
        val label: String,
        val modelValue: String,
        val nativeFaceInstallSupported: Boolean,
        val formatLabel: String,
        val compatibilityNote: String,
        val aliases: List<String>
    )

    private fun target(
        id: String,
        familyId: String,
        width: Int,
        height: Int,
        label: String,
        modelValue: String,
        nativeFaceInstallSupported: Boolean,
        compatibilityNote: String,
        vararg aliases: String
    ) = SupportedTarget(
        id = id,
        familyId = familyId,
        width = width,
        height = height,
        label = label,
        modelValue = modelValue,
        nativeFaceInstallSupported = nativeFaceInstallSupported,
        formatLabel = if (nativeFaceInstallSupported) NATIVE_FORMAT_LABEL else "Preview only — model-specific package format not implemented",
        compatibilityNote = compatibilityNote,
        aliases = (listOf(modelValue, label) + aliases).distinct()
    )

    private val knownTargetProfiles = listOf(
        target("band11-active", "band11-active", 172, 320,
            "Xiaomi Smart Band 11 Active", "Xiaomi Smart Band 11 Active", false,
            "Band 11 Active uses a distinct 172 × 320 px display. Its package format has not been validated by MIIT; preview/save only."),
        target("band11-ceramic", "band11", 212, 520,
            "Xiaomi Smart Band 11 Ceramic Edition", "Xiaomi Smart Band 11 Ceramic Edition", false,
            "Uses the standard Band 11 display profile (212 × 520 px), but Band 11 package compatibility is not yet validated."),
        target("band11-nfc", "band11", 212, 520,
            "Xiaomi Smart Band 11 NFC Edition", "Xiaomi Smart Band 11 NFC Edition", false,
            "Uses the standard Band 11 display profile (212 × 520 px), but Band 11 package compatibility is not yet validated.",
            "Smart Band 11 NFC", "Xiaomi Smart Band 11 NFC"),
        target("band11", "band11", 212, 520,
            "Xiaomi Smart Band 11", "Xiaomi Smart Band 11", false,
            "Uses the standard Band 11 display profile (212 × 520 px), but Band 11 package compatibility is not yet validated.",
            "Smart Band 11", "Xiaomi Band 11"),
        target("band10-pro-nfc-ceramic", "band10-pro", 336, 480,
            "Xiaomi Smart Band 10 Pro NFC — Ceramic Edition", "Xiaomi Smart Band 10 Pro NFC Ceramic Edition", false,
            "Pro/Ceramic profile recognised (336 × 480 px), but direct installation is blocked until a Pro-specific package writer is validated.",
            "Smart Band 10 Pro NFC Ceramic", "Xiaomi Smart Band 10 Pro Ceramic Edition"),
        target("band10-pro-nfc", "band10-pro", 336, 480,
            "Xiaomi Smart Band 10 Pro NFC", "Xiaomi Smart Band 10 Pro NFC", false,
            "Pro profile recognised (336 × 480 px), but the standard-band .face writer is not validated for Pro devices."),
        target("band10-pro", "band10-pro", 336, 480,
            "Xiaomi Smart Band 10 Pro", "Xiaomi Smart Band 10 Pro", false,
            "Pro profile recognised (336 × 480 px), but the standard-band .face writer is not validated for Pro devices."),
        target("band10-glimmer", "band10", 212, 520,
            "Xiaomi Smart Band 10 Glimmer Edition", "Xiaomi Smart Band 10 Glimmer Edition", true,
            "Uses the standard Band 10 display profile (212 × 520 px). Country/firmware compatibility still requires validation."),
        target("band10-ceramic", "band10", 212, 520,
            "Xiaomi Smart Band 10 Ceramic Edition", "Xiaomi Smart Band 10 Ceramic Edition", true,
            "Uses the standard Band 10 display profile (212 × 520 px). Country/firmware compatibility still requires validation."),
        target("band10-nfc", "band10", 212, 520,
            "Xiaomi Smart Band 10 NFC Edition", "Xiaomi Smart Band 10 NFC Edition", true,
            "Uses the standard Band 10 display profile (212 × 520 px). Country/firmware compatibility still requires validation.",
            "Smart Band 10 NFC", "Xiaomi Smart Band 10 NFC"),
        target("band10", "band10", 212, 520,
            "Xiaomi Smart Band 10", "Xiaomi Smart Band 10", true,
            "Uses MIIT's current standard Band 10 .face writer (212 × 520 px); regional firmware is not inferred from the model label."),
        target("band9-pro-nfc", "band9-pro", 336, 480,
            "Xiaomi Smart Band 9 Pro NFC Edition", "Xiaomi Smart Band 9 Pro NFC Edition", false,
            "Pro profile recognised (336 × 480 px), but direct installation is blocked until a Pro-specific package writer is validated.",
            "Smart Band 9 Pro NFC", "Xiaomi Smart Band 9 Pro NFC"),
        target("band9-pro", "band9-pro", 336, 480,
            "Xiaomi Smart Band 9 Pro", "Xiaomi Smart Band 9 Pro", false,
            "Pro profile recognised (336 × 480 px), but the standard-band .face writer is not validated for Pro devices."),
        target("band9-nfc", "band9", 192, 490,
            "Xiaomi Smart Band 9 NFC Edition", "Xiaomi Smart Band 9 NFC Edition", true,
            "Uses the standard Band 9 display profile (192 × 490 px). Country/firmware compatibility still requires validation.",
            "Smart Band 9 NFC", "Xiaomi Smart Band 9 NFC"),
        target("band9", "band9", 192, 490,
            "Xiaomi Smart Band 9", "Xiaomi Smart Band 9", true,
            "Uses MIIT's current standard Band 9 .face writer (192 × 490 px); regional firmware is not inferred from the model label."),
        target("band8-pro", "band8-pro", 336, 480,
            "Xiaomi Smart Band 8 Pro", "Xiaomi Smart Band 8 Pro", false,
            "Pro profile recognised (336 × 480 px), but the standard-band .face writer is not validated for Pro devices."),
        target("band8-nfc", "band8", 192, 490,
            "Xiaomi Smart Band 8 NFC Edition", "Xiaomi Smart Band 8 NFC Edition", false,
            "Editor profile recognised (192 × 490 px), but Band 8 package compatibility has not been validated by MIIT.",
            "Smart Band 8 NFC", "Xiaomi Smart Band 8 NFC"),
        target("band8", "band8", 192, 490,
            "Xiaomi Smart Band 8", "Xiaomi Smart Band 8", false,
            "Editor profile recognised (192 × 490 px), but Band 8 package compatibility has not been validated by MIIT."),
        target("band7-pro", "band7-pro", 280, 456,
            "Xiaomi Smart Band 7 Pro", "Xiaomi Smart Band 7 Pro", false,
            "Pro profile recognised (280 × 456 px), but Band 7 Pro needs a different package path that MIIT has not implemented."),
        target("band7-nfc", "band7", 192, 490,
            "Xiaomi Smart Band 7 NFC Edition", "Xiaomi Smart Band 7 NFC Edition", false,
            "Editor profile recognised (192 × 490 px), but Band 7 uses a different package path that MIIT has not implemented.",
            "Mi Smart Band 7 NFC", "Smart Band 7 NFC", "Xiaomi Smart Band 7 NFC"),
        target("band7", "band7", 192, 490,
            "Xiaomi Smart Band 7", "Xiaomi Smart Band 7", false,
            "Editor profile recognised (192 × 490 px), but Band 7 uses a different package path that MIIT has not implemented.",
            "Mi Smart Band 7", "Smart Band 7")
    )

    fun knownTargets(): List<SupportedTarget> = knownTargetProfiles.toList()

    /**
     * Normalize whitespace and Unicode representation without truncating a title.
     */
    fun normalizeWatchfaceName(value: String): String =
        Normalizer.normalize(value.trim().replace(Regex("\\s+"), " "), Normalizer.Form.NFC)

    /**
     * Xiaomi does not publish a formal title-character policy for this reverse-engineered
     * container. MIIT accepts printable Unicode, blocks controls/path-reserved characters,
     * and validates the UTF-8 byte limit of its current title buffer.
     */
    fun validateWatchfaceName(value: String): List<String> {
        val errors = mutableListOf<String>()
        if (value.any { ch -> Character.isISOControl(ch) }) {
            errors += "Screen name cannot contain control characters or line breaks."
        }
        val reserved = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val normalized = normalizeWatchfaceName(value)
        if (normalized.isBlank()) errors += "Enter a screen name before saving or installing."
        if (normalized.any { ch -> ch in reserved }) {
            errors += "Avoid reserved filename characters such as slash, backslash, colon, asterisk, question mark, quote, angle brackets, or vertical bar."
        }
        if (normalized.toByteArray(Charsets.UTF_8).size > MAX_WATCHFACE_NAME_UTF8_BYTES) {
            errors += "Screen name must fit within $MAX_WATCHFACE_NAME_UTF8_BYTES UTF-8 bytes for the current Band title field."
        }
        return errors.distinct()
    }

    fun supportedTarget(model: String?, name: String?): SupportedTarget? {
        val normalizedModel = normalizeModel(model.orEmpty())
        val normalizedName = normalizeModel(name.orEmpty())
        // Prefer a readable model field. Bluetooth names are fallback only when model is opaque.
        val candidate = if (normalizedModel.contains("band")) normalizedModel else normalizedName
        val candidateForms = listOf(candidate, candidate.removePrefix("xiaomi "), candidate.removePrefix("mi "))
        return knownTargetProfiles.firstOrNull { profile ->
            profile.aliases.any { alias ->
                val normalizedAlias = normalizeModel(alias)
                val aliasForms = listOf(
                    normalizedAlias,
                    normalizedAlias.removePrefix("xiaomi "),
                    normalizedAlias.removePrefix("mi ")
                )
                candidateForms.any { candidateForm ->
                    aliasForms.any { aliasForm ->
                        candidateForm == aliasForm || candidateForm.startsWith("$aliasForm ")
                    }
                }
            }
        }
    }

    fun inspectPackage(id: String, bytes: ByteArray): PackageReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val hash = sha256(bytes)

        if (!Regex("[0-9]{9}").matches(id)) {
            errors += "Watch-face ID must contain exactly 9 decimal digits."
        }
        if (bytes.isEmpty()) {
            errors += "Watch-face package is empty."
            return PackageReport(false, errors, warnings, null, null, null, 0, hash)
        }
        if (bytes.size > MAX_PACKAGE_BYTES) {
            errors += "Package exceeds MIIT's 4 MB safety ceiling."
        }
        if (bytes.size < MAIN_HEADER_SIZE + FACE_HEADER_SIZE) {
            errors += "Package is too small for Xiaomi face headers."
            return PackageReport(false, errors, warnings, null, null, null, bytes.size, hash)
        }

        val magicOk = readU8(bytes, 0) == 0x5A &&
            readU8(bytes, 1) == 0xA5 &&
            readU8(bytes, 2) == 0x34 &&
            readU8(bytes, 3) == 0x12
        if (!magicOk) errors += "Invalid Xiaomi watch-face magic header."

        val storedId = readFixedUtf8(bytes, 0x28, 9)
        if (storedId != id) errors += "Package ID does not match the install request."

        val faceCount = readU16(bytes, 0x1C)
        val sectionCount = readU16(bytes, 0x1E)
        val previewOffset = readU32(bytes, 0x20)?.toInt()

        if (faceCount == null || faceCount !in 1..2) {
            errors += "Invalid face count."
        } else {
            val expectedSections = if (faceCount == 1) 1 else 4
            if (sectionCount != expectedSections) errors += "Invalid main section count."

            val headersEnd = MAIN_HEADER_SIZE + faceCount * FACE_HEADER_SIZE
            if (headersEnd > bytes.size) {
                errors += "Face headers exceed package bounds."
            } else {
                var definitionStart = headersEnd
                var detectedWidth: Int? = null
                var detectedHeight: Int? = null

                repeat(faceCount) { faceIndex ->
                    val faceOffset = MAIN_HEADER_SIZE + faceIndex * FACE_HEADER_SIZE
                    val defCount = readU32(bytes, faceOffset + 0x08)?.toInt()
                    val defOffset = readU32(bytes, faceOffset + 0x0C)?.toInt()
                    val resCount = readU32(bytes, faceOffset + 0x18)?.toInt()
                    val resOffset = readU32(bytes, faceOffset + 0x1C)?.toInt()
                    val facePreview = readU32(bytes, faceOffset + 0x04)?.toInt()

                    if (defCount != 1) errors += "Face " + faceIndex + " has an invalid definition count."
                    if (resCount != 1) errors += "Face " + faceIndex + " has an invalid resource count."
                    if (defOffset != definitionStart) errors += "Face " + faceIndex + " definition offset is inconsistent."
                    if (resOffset != definitionStart + DEFINITION_SIZE) {
                        errors += "Face " + faceIndex + " resource table offset is inconsistent."
                    }

                    val propertyStart = readU32(bytes, definitionStart + 0x08)?.toInt()
                    val propertyLength = readU32(bytes, definitionStart + 0x0C)?.toInt()
                    val imageDefinitionStart = definitionStart + DEFINITION_SIZE
                    val imageStart = readU32(bytes, imageDefinitionStart + 0x08)?.toInt()
                    val imageLength = readU32(bytes, imageDefinitionStart + 0x0C)?.toInt()

                    if (readU24(bytes, definitionStart) != 0 || readU8(bytes, definitionStart + 3) != 0) {
                        errors += "Face " + faceIndex + " property definition is invalid."
                    }
                    if (propertyStart == null || propertyLength != ELEMENT_PROPERTY_SIZE) {
                        errors += "Face " + faceIndex + " property block is invalid."
                    }
                    if (readU8(bytes, (propertyStart ?: -1) + 3) != 2) {
                        errors += "Face " + faceIndex + " property element type is invalid."
                    }
                    if (readU24(bytes, imageDefinitionStart) != 0 || readU8(bytes, imageDefinitionStart + 3) != 2) {
                        errors += "Face " + faceIndex + " image definition is invalid."
                    }

                    if (imageStart == null || imageLength == null || imageLength < IMAGE_HEADER_SIZE) {
                        errors += "Face " + faceIndex + " image resource is invalid."
                        return@repeat
                    }
                    if (imageStart < 0 || imageStart + imageLength > bytes.size) {
                        errors += "Face " + faceIndex + " image resource exceeds package bounds."
                        return@repeat
                    }

                    val width = readU16(bytes, imageStart + 4)
                    val height = readU16(bytes, imageStart + 6)
                    val rawSize = readU32(bytes, imageStart + 8)?.toInt()

                    if (readU8(bytes, imageStart) != 0 || readU8(bytes, imageStart + 1) != 0) {
                        errors += "Face " + faceIndex + " image block header is invalid."
                    }
                    if (width == null || height == null || rawSize == null) {
                        errors += "Face " + faceIndex + " image dimensions are unreadable."
                        return@repeat
                    }

                    val expectedRaw = width.toLong() * height.toLong() * 4L
                    if (expectedRaw > Int.MAX_VALUE || rawSize.toLong() != expectedRaw) {
                        errors += "Face " + faceIndex + " image byte count is inconsistent."
                    }
                    if (imageLength != IMAGE_HEADER_SIZE + rawSize) {
                        errors += "Face " + faceIndex + " image resource length is inconsistent."
                    }

                    if (detectedWidth == null) {
                        detectedWidth = width
                        detectedHeight = height
                    } else if (detectedWidth != width || detectedHeight != height) {
                        errors += "Active and AOD images use different resolutions."
                    }

                    if (faceIndex == 0) {
                        if (facePreview != imageStart) errors += "Active-face preview pointer is invalid."
                        if (previewOffset != imageStart) errors += "Main preview pointer is invalid."
                    } else if (facePreview != 0) {
                        errors += "AOD preview pointer must be zero for MIIT's current format."
                    }

                    definitionStart = imageStart + imageLength
                    if (faceIndex == faceCount - 1 && definitionStart != bytes.size) {
                        errors += "Package contains trailing or missing resource bytes."
                    }
                }

                if (!((detectedWidth == 212 && detectedHeight == 520) ||
                    (detectedWidth == 192 && detectedHeight == 490))
                ) {
                    errors += "Unsupported watch-face resolution for MIIT's current .face installer."
                }
                if (faceCount == 2) {
                    warnings += "AOD is enabled. Xiaomi documents higher AOD power use and possible flicker on large AOD areas."
                }

                return PackageReport(
                    safe = errors.isEmpty(),
                    errors = errors.distinct(),
                    warnings = warnings.distinct(),
                    width = detectedWidth,
                    height = detectedHeight,
                    faceCount = faceCount,
                    sizeBytes = bytes.size,
                    sha256 = hash
                )
            }
        }

        return PackageReport(errors.isEmpty(), errors.distinct(), warnings.distinct(), null, null, faceCount, bytes.size, hash)
    }

    fun preflightBand(
        context: Context,
        device: BandDevice?,
        report: PackageReport,
        requestedId: String,
        requestedName: String? = null,
        requestedModel: String? = null,
        requestedCountryVariant: String? = null,
        requestedFormat: String? = null
    ): PreflightReport {
        val errors = report.errors.toMutableList()
        val warnings = report.warnings.toMutableList()
        requestedName?.let { errors += validateWatchfaceName(it) }
        if (requestedFormat != null && requestedFormat != NATIVE_FORMAT_LABEL) {
            errors += "Unsupported export format. MIIT can directly install only its current Xiaomi .face format."
        }

        if (device == null) errors += "Connected Band identity is unavailable."
        if (device?.connected != true || device.authenticated != true) {
            errors += "The Band is not fully connected and authenticated."
        }

        val expected = supportedTarget(device?.model, device?.name)
        if (expected == null) {
            errors += "Unsupported or ambiguous Band model. MIIT will never guess a target profile for installation."
        } else {
            if (!expected.nativeFaceInstallSupported) {
                errors += "Direct installation is blocked for " + expected.label + ": " + expected.compatibilityNote
            }
            if (report.width != expected.width || report.height != expected.height) {
                errors += "Package resolution does not match the connected Band."
            }
            if (requestedModel.isNullOrBlank()) {
                errors += "Screen model/profile is blank."
            } else {
                val selected = supportedTarget(requestedModel, null)
                if (selected == null || !selected.nativeFaceInstallSupported ||
                    selected.familyId != expected.familyId ||
                    selected.width != expected.width || selected.height != expected.height
                ) {
                    errors += "The editable model/profile must use the same supported native-format family and exact resolution as the detected Band."
                }
            }
            val detectedVariant = device?.countryVariant?.trim().orEmpty()
            val requestedVariant = requestedCountryVariant?.trim().orEmpty()
            if (detectedVariant.isNotBlank() && !requestedVariant.equals(detectedVariant, ignoreCase = true)) {
                errors += "Screen region/variant must match the connected Band's detected variant."
            } else if (requestedVariant.isNotBlank() && detectedVariant.isBlank()) {
                warnings += "The region/variant is user-entered metadata; MIIT cannot verify regional firmware compatibility from that label alone."
            }
        }

        val bandBattery = device?.batteryPercentage
        when {
            bandBattery == null -> errors += "Band battery level is unavailable."
            bandBattery < MIN_BAND_BATTERY_PERCENT ->
                errors += "Band battery is below MIIT's " + MIN_BAND_BATTERY_PERCENT + "% installation threshold."
        }

        val phoneBattery = readPhoneBatteryPercent(context)
        if (phoneBattery != null && phoneBattery < MIN_PHONE_BATTERY_PERCENT) {
            errors += "Phone battery is below MIIT's " + MIN_PHONE_BATTERY_PERCENT + "% installation threshold."
        }

        if (device?.watchfaces.isNullOrEmpty()) {
            errors += "Current Band watch-face inventory has not been received yet."
        } else {
            if (device?.watchfaces?.none { it.active } == true) {
                errors += "MIIT cannot identify the currently active watch face."
            }
            val downloadable = device.watchfaces.count { it.canDelete }
            if (expected?.width == 212 && downloadable >= 9) {
                errors += "Band 10 already reports 9 downloadable faces; free a market-face slot first."
            } else if (expected?.width == 192 && downloadable >= 8) {
                warnings += "Band 9 is reporting many downloadable faces; its watch-face storage is limited."
            }
        }

        if (device?.firmware.isNullOrBlank()) {
            warnings += "Firmware version is unknown; cross-firmware compatibility is not proven."
        }
        if (device?.charging == true) {
            warnings += "Band is charging; keep that connection physically stable during the transfer."
        } else {
            warnings += "Keep phone and Band close together and do not leave MIIT during the transfer."
        }
        if (device?.watchfaces?.any { it.code == requestedId } == true) {
            errors += "This face ID already exists on the Band; replacement is blocked."
        }

        return PreflightReport(errors.isEmpty(), errors.distinct(), warnings.distinct())
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun normalizeModel(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun readFixedUtf8(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset < 0 || offset + length > bytes.size) return null
        var end = offset + length
        while (end > offset && bytes[end - 1].toInt() == 0) end--
        return bytes.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun readPhoneBatteryPercent(context: Context): Int? {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val value = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return value.takeIf { it in 0..100 }
    }

    private fun readU8(bytes: ByteArray, offset: Int): Int? =
        if (offset >= 0 && offset < bytes.size) bytes[offset].toInt() and 0xFF else null

    private fun readU16(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 2 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readU24(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 3 > bytes.size) return null
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
