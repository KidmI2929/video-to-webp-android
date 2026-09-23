package com.kidmi.videotowebp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoder
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoderOptions
import com.aureusapps.android.webpandroid.encoder.WebPConfig
import com.aureusapps.android.webpandroid.encoder.WebPMuxAnimParams
import com.aureusapps.android.webpandroid.encoder.WebPPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class ConversionSettings(
    val startMs: Long,
    val endMs: Long,
    val fps: Int,
    val quality: Int,
    val maxSide: Int?,
    val lossless: Boolean,
    val loopForever: Boolean
)

data class ConversionResult(
    val uri: Uri,
    val fileName: String,
    val sizeBytes: Long
)

class ConversionEngine(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var currentEncoder: WebPAnimEncoder? = null

    fun convert(
        sourceUri: Uri,
        settings: ConversionSettings,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (ConversionResult) -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        cancel()
        cancelled.set(false)

        Thread {
            val retriever = MediaMetadataRetriever()
            var encoder: WebPAnimEncoder? = null
            var pendingUri: Uri? = null
            var firstFrame: Bitmap? = null

            try {
                post { onStatus("영상 프레임 준비 중…") }
                post { onProgress(0.01f) }

                retriever.setDataSource(context, sourceUri)

                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                    ?.let { ((it % 360) + 360) % 360 }
                    ?: 0

                val startMs = settings.startMs.coerceAtLeast(0L)
                val endMs = settings.endMs.coerceAtLeast(startMs + 100L)
                val clipDurationMs = endMs - startMs
                val safeFps = settings.fps.coerceIn(1, 30)
                val frameIntervalMs = (1000.0 / safeFps)
                    .roundToLong()
                    .coerceAtLeast(1L)

                val frameCount = max(
                    1,
                    ceil(clipDurationMs / frameIntervalMs.toDouble()).toInt()
                )

                val rawFirst = requireNotNull(
                    retriever.getFrameAtTime(
                        startMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                ) { "첫 영상 프레임을 읽을 수 없습니다." }

                firstFrame = orientBitmap(rawFirst, rotation)
                if (firstFrame !== rawFirst) {
                    rawFirst.recycle()
                }

                val (targetWidth, targetHeight) = calculateTargetSize(
                    firstFrame.width,
                    firstFrame.height,
                    settings.maxSide
                )

                pendingUri = createPendingDestination()

                encoder = WebPAnimEncoder(
                    context = context,
                    width = targetWidth,
                    height = targetHeight,
                    options = WebPAnimEncoderOptions(
                        minimizeSize = true,
                        allowMixed = !settings.lossless,
                        animParams = WebPMuxAnimParams(
                            backgroundColor = 0x00000000,
                            loopCount = if (settings.loopForever) 0 else 1
                        )
                    )
                )
                currentEncoder = encoder

                encoder.configure(
                    config = WebPConfig(
                        lossless = if (settings.lossless) {
                            WebPConfig.COMPRESSION_LOSSLESS
                        } else {
                            WebPConfig.COMPRESSION_LOSSY
                        },
                        quality = settings.quality.coerceIn(1, 100).toFloat(),
                        method = 6,
                        threadLevel = 1,
                        alphaQuality = settings.quality.coerceIn(1, 100)
                    ),
                    preset = WebPPreset.WEBP_PRESET_PICTURE
                )

                encoder.addProgressListener { _, _ ->
                    !cancelled.get()
                }

                post { onStatus("프레임 추출 및 WebP 인코딩 중…") }

                for (index in 0 until frameCount) {
                    if (cancelled.get()) {
                        finishCancelled(pendingUri, onCancelled)
                        return@Thread
                    }

                    val relativeMs = minOf(
                        index * frameIntervalMs,
                        clipDurationMs - 1L
                    ).coerceAtLeast(0L)

                    var oriented: Bitmap? = null
                    var scaled: Bitmap? = null

                    try {
                        oriented = if (index == 0) {
                            firstFrame.also { firstFrame = null }
                        } else {
                            val raw = requireNotNull(
                                retriever.getFrameAtTime(
                                    (startMs + relativeMs) * 1000L,
                                    MediaMetadataRetriever.OPTION_CLOSEST
                                )
                            ) { "영상 프레임 ${index + 1}을 읽을 수 없습니다." }

                            val corrected = orientBitmap(raw, rotation)
                            if (corrected !== raw) {
                                raw.recycle()
                            }
                            corrected
                        }

                        val frame = requireNotNull(oriented)
                        scaled = if (
                            frame.width == targetWidth &&
                            frame.height == targetHeight
                        ) {
                            frame
                        } else {
                            Bitmap.createScaledBitmap(
                                frame,
                                targetWidth,
                                targetHeight,
                                true
                            )
                        }

                        encoder.addFrame(relativeMs, scaled)

                        val fraction = (index + 1f) / frameCount.toFloat()
                        post { onProgress(0.03f + fraction * 0.87f) }
                    } finally {
                        if (scaled != null && scaled !== oriented && !scaled.isRecycled) {
                            scaled.recycle()
                        }
                        if (oriented != null && !oriented.isRecycled) {
                            oriented.recycle()
                        }
                    }
                }

                if (cancelled.get()) {
                    finishCancelled(pendingUri, onCancelled)
                    return@Thread
                }

                post { onStatus("Animated WebP 마무리 중…") }
                post { onProgress(0.92f) }

                val completedUri = requireNotNull(pendingUri)
                encoder.assemble(clipDurationMs, completedUri)
                finalizePending(completedUri)

                val fileName = queryDisplayName(context, completedUri)
                    ?: "VideoToWebP.webp"
                val sizeBytes = querySize(completedUri)

                post { onProgress(1f) }
                post {
                    onComplete(
                        ConversionResult(
                            uri = completedUri,
                            fileName = fileName,
                            sizeBytes = sizeBytes
                        )
                    )
                }

                pendingUri = null
            } catch (e: Exception) {
                pendingUri?.let { deleteDestination(it) }

                if (cancelled.get()) {
                    post(onCancelled)
                } else {
                    post {
                        onError(
                            e.message
                                ?: "Animated WebP 변환 중 오류가 발생했습니다."
                        )
                    }
                }
            } finally {
                firstFrame?.let {
                    if (!it.isRecycled) {
                        it.recycle()
                    }
                }
                try {
                    encoder?.release()
                } catch (_: Exception) {
                }
                if (currentEncoder === encoder) {
                    currentEncoder = null
                }
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    fun cancel() {
        cancelled.set(true)
        try {
            currentEncoder?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun finishCancelled(
        uri: Uri?,
        onCancelled: () -> Unit
    ) {
        uri?.let { deleteDestination(it) }
        post(onCancelled)
    }

    private fun calculateTargetSize(
        width: Int,
        height: Int,
        maxSide: Int?
    ): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val limit = maxSide ?: return safeWidth to safeHeight
        val largest = max(safeWidth, safeHeight)

        if (largest <= limit) {
            return safeWidth to safeHeight
        }

        val scale = limit / largest.toDouble()
        val outWidth = (safeWidth * scale)
            .roundToInt()
            .coerceAtLeast(1)
        val outHeight = (safeHeight * scale)
            .roundToInt()
            .coerceAtLeast(1)

        return outWidth to outHeight
    }

    private fun orientBitmap(
        bitmap: Bitmap,
        rotation: Int
    ): Bitmap {
        if (rotation == 0) {
            return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun createPendingDestination(): Uri {
        val resolver = context.contentResolver
        val fileName = "VideoToWebP_" +
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date()) +
            ".webp"

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                fileName
            )
            put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/webp"
            )
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/VideoToWebP"
            )
            put(
                MediaStore.Images.Media.IS_PENDING,
                1
            )
        }

        return requireNotNull(
            resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )
        ) {
            "갤러리에 저장 위치를 만들 수 없습니다."
        }
    }

    private fun finalizePending(uri: Uri) {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.IS_PENDING,
                0
            )
        }

        context.contentResolver.update(
            uri,
            values,
            null,
            null
        )
    }

    private fun deleteDestination(uri: Uri) {
        try {
            context.contentResolver.delete(
                uri,
                null,
                null
            )
        } catch (_: Exception) {
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            context.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { descriptor ->
                    descriptor.length.coerceAtLeast(0L)
                }
                ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
