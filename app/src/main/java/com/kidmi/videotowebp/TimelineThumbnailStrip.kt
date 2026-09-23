package com.kidmi.videotowebp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class TimelineThumbnail(
    val timeMs: Long,
    val bitmap: Bitmap
)

@Composable
fun TimelineThumbnailStrip(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    currentMs: Long,
    onSeek: (Long) -> Unit
) {
    var thumbnails by remember(uri, startMs, endMs) {
        mutableStateOf<List<TimelineThumbnail>>(emptyList())
    }

    LaunchedEffect(uri, startMs, endMs) {
        thumbnails = withContext(Dispatchers.IO) {
            loadTimelineThumbnails(
                context = context,
                uri = uri,
                startMs = startMs,
                endMs = endMs,
                count = 10
            )
        }
    }

    DisposableEffect(thumbnails) {
        onDispose {
            thumbnails.forEach {
                runCatching {
                    if (!it.bitmap.isRecycled) {
                        it.bitmap.recycle()
                    }
                }
            }
        }
    }

    if (thumbnails.isEmpty()) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                "타임라인 썸네일 준비 중…",
                modifier = Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 10.dp
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    val nearest = thumbnails.minByOrNull {
        abs(it.timeMs - currentMs)
    }?.timeMs

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        items(
            items = thumbnails,
            key = { it.timeMs }
        ) { item ->
            val selected = item.timeMs == nearest
            Surface(
                modifier = Modifier
                    .width(78.dp)
                    .height(54.dp)
                    .clickable {
                        onSeek(item.timeMs)
                    },
                shape = RoundedCornerShape(10.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                tonalElevation = if (selected) 3.dp else 0.dp
            ) {
                Box(
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Image(
                        bitmap = item.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .width(78.dp)
                            .height(54.dp)
                            .clip(RoundedCornerShape(9.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(
                            alpha = 0.72f
                        ),
                        shape = RoundedCornerShape(5.dp)
                    ) {
                        Text(
                            formatDetailedTime(item.timeMs),
                            modifier = Modifier.padding(
                                horizontal = 4.dp,
                                vertical = 1.dp
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

private fun loadTimelineThumbnails(
    context: Context,
    uri: Uri,
    startMs: Long,
    endMs: Long,
    count: Int
): List<TimelineThumbnail> {
    val retriever = MediaMetadataRetriever()

    return try {
        retriever.setDataSource(context, uri)

        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = endMs.coerceAtLeast(safeStart + 1L)
        val safeCount = count.coerceIn(4, 14)

        List(safeCount) { index ->
            val fraction =
                if (safeCount <= 1) 0.0
                else index.toDouble() / (safeCount - 1)

            val timeMs =
                safeStart +
                    ((safeEnd - safeStart) * fraction)
                        .toLong()

            val bitmap = runCatching {
                retriever.getScaledFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    240,
                    135
                )
            }.getOrNull() ?: runCatching {
                retriever.getFrameAtTime(
                    timeMs * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            }.getOrNull()

            bitmap?.let {
                TimelineThumbnail(
                    timeMs = timeMs,
                    bitmap = it
                )
            }
        }.filterNotNull()
    } finally {
        retriever.release()
    }
}
