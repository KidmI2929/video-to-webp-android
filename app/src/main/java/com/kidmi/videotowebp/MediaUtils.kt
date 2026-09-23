package com.kidmi.videotowebp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale
import kotlin.math.max

data class VideoInfo(
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sourceFps: Float?
)

fun readVideoInfo(context: Context, uri: Uri): VideoInfo {
    val name = queryDisplayName(context, uri) ?: "video"
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(context, uri)

        val duration = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L

        var width = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?: 0

        var height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?: 0

        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0

        if (rotation == 90 || rotation == 270) {
            val tmp = width
            width = height
            height = tmp
        }

        val sourceFps = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > 0.1f && it < 1000f }

        VideoInfo(
            displayName = name,
            durationMs = max(0L, duration),
            width = width,
            height = height,
            sourceFps = sourceFps
        )
    } finally {
        retriever.release()
    }
}

fun queryDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

fun formatDetailedTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L

    return if (hours > 0L) {
        String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            hours,
            minutes,
            seconds,
            millis
        )
    } else {
        String.format(
            Locale.US,
            "%02d:%02d.%03d",
            minutes,
            seconds,
            millis
        )
    }
}

fun frameIndexAt(ms: Long, fps: Float?): Long? {
    val safeFps = fps?.takeIf { it.isFinite() && it > 0.1f } ?: return null
    return ((ms.coerceAtLeast(0L) / 1000.0) * safeFps).toLong()
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
