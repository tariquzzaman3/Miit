package com.miit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Finds an existing Xiaomi watch-face preview from Mi Fitness' local cache.
 * Mi Fitness has used both com.xiaomi.wearable and com.mi.health package paths.
 * The cache filename is not treated as the watch-face name.
 */
object WatchfacePreviewResolver {
    data class Result(val file: File, val source: String)

    private val roots = listOf(
        "/storage/emulated/0/Android/data/com.xiaomi.wearable/files/WatchFace",
        "/storage/emulated/0/Android/data/com.xiaomi.wearable/files/watchFace",
        "/storage/emulated/0/Android/data/com.mi.health/files/WatchFace",
        "/storage/emulated/0/Android/data/com.mi.health/files/watchFace"
    )

    fun find(context: Context, display: com.miit.app.band.BandDisplay, model: String?): Result? {
        val code = display.code.orEmpty().lowercase()
        val name = display.name.orEmpty().lowercase()
        val files = roots.asSequence()
            .map(::File)
            .filter { it.isDirectory }
            .flatMap { walk(it, 3).asSequence() }
            .filter { it.isFile }
            .take(500)
            .toList()

        // Exact ID/name matches first.
        val ranked = files.sortedByDescending { file ->
            val n = file.name.lowercase()
            when {
                code.isNotBlank() && n.contains(code) -> 100
                name.isNotBlank() && n.contains(name.replace(" ", "_")) -> 80
                n.contains("preview") -> 40
                else -> 0
            }
        }

        for (file in ranked) {
            val candidate = extractPreview(context, file, model) ?: continue
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(candidate.absolutePath, opts)
            if (opts.outWidth > 30 && opts.outHeight > 30) {
                return Result(candidate, file.absolutePath)
            }
        }
        return null
    }

    private fun walk(root: File, depth: Int): List<File> {
        if (depth < 0) return emptyList()
        val children = root.listFiles() ?: return emptyList()
        return children.flatMap { child ->
            if (child.isDirectory) walk(child, depth - 1) else listOf(child)
        }
    }

    private fun extractPreview(context: Context, source: File, model: String?): File? {
        val n = source.name.lowercase()
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")) {
            return source
        }

        return runCatching {
            ZipFile(source).use { zip ->
                val entries = zip.entries().toList()
                val sorted = entries
                    .filter { !it.isDirectory }
                    .sortedBy { entry ->
                        val p = entry.name.lowercase()
                        when {
                            p.contains("images_preview") -> 0
                            p.contains("preview") -> 1
                            p.endsWith("static.png") -> 2
                            p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".webp") -> 3
                            else -> 9
                        }
                    }

                val entry = sorted.firstOrNull {
                    val p = it.name.lowercase()
                    p.contains("preview") ||
                        p.endsWith("static.png") ||
                        p.endsWith(".png") || p.endsWith(".jpg") ||
                        p.endsWith(".jpeg") || p.endsWith(".webp")
                } ?: return@use null

                val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) } ?: return@use null
                if (bitmap.width < 30 || bitmap.height < 30) return@use null

                val safeName = "watchface_preview_" + source.name.hashCode().toString() + ".png"
                val out = File(context.cacheDir, safeName)
                if (!out.exists()) {
                    FileOutputStream(out).use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                }
                bitmap.recycle()
                out
            }
        }.getOrNull()
    }
}
