package com.kidmi.videotowebp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class FocusKeyframe(
    val timeMs: Long,
    val x: Float,
    val y: Float
)

class FaceTrackingAnalyzer(
    private val context: Context
) {
    private val cancelled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancel() {
        cancelled.set(true)
    }

    fun analyze(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        initialFocusX: Float,
        initialFocusY: Float,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit,
        onComplete: (List<FocusKeyframe>) -> Unit,
        onError: (String) -> Unit,
        onCancelled: () -> Unit
    ) {
        cancel()
        cancelled.set(false)

        Thread {
            val detector = FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(
                        FaceDetectorOptions.PERFORMANCE_MODE_FAST
                    )
                    .setLandmarkMode(
                        FaceDetectorOptions.LANDMARK_MODE_NONE
                    )
                    .setContourMode(
                        FaceDetectorOptions.CONTOUR_MODE_NONE
                    )
                    .setClassificationMode(
                        FaceDetectorOptions.CLASSIFICATION_MODE_NONE
                    )
                    .setMinFaceSize(0.07f)
                    .enableTracking()
                    .build()
            )

            val retriever = MediaMetadataRetriever()

            try {
                post { onStatus("얼굴 추적 준비 중…") }
                post { onProgress(0.01f) }

                retriever.setDataSource(context, sourceUri)

                val rotation = retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                    )
                    ?.toIntOrNull()
                    ?.let { ((it % 360) + 360) % 360 }
                    ?: 0

                val rawWidth = retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                    )
                    ?.toIntOrNull()
                    ?.coerceAtLeast(1)
                    ?: 640

                val rawHeight = retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                    )
                    ?.toIntOrNull()
                    ?.coerceAtLeast(1)
                    ?: 360

                val clipStart = startMs.coerceAtLeast(0L)
                val clipEnd = endMs.coerceAtLeast(clipStart + 100L)
                val duration = clipEnd - clipStart

                // Keep analysis responsive on phones: at most about 72
                // detection samples, while still sampling moving faces
                // around 4 times per second for short clips.
                val sampleInterval = max(
                    250L,
                    ceil(duration / 72.0).toLong()
                )
                val sampleTimes = buildList {
                    var t = clipStart
                    while (t < clipEnd) {
                        add(t)
                        t += sampleInterval
                    }
                    if (isEmpty() || last() < clipEnd - 40L) {
                        add(clipEnd - 1L)
                    }
                }

                val scale = min(
                    1.0,
                    640.0 / max(rawWidth, rawHeight).toDouble()
                )
                val decodeWidth = max(
                    2,
                    (rawWidth * scale).toInt()
                )
                val decodeHeight = max(
                    2,
                    (rawHeight * scale).toInt()
                )

                var preferredTrackingId: Int? = null
                var previousX = initialFocusX.coerceIn(0f, 1f)
                var previousY = initialFocusY.coerceIn(0f, 1f)
                var smoothedX = previousX
                var smoothedY = previousY
                var hasPoint = false

                val points = mutableListOf<FocusKeyframe>()

                sampleTimes.forEachIndexed { index, timeMs ->
                    if (cancelled.get()) {
                        post(onCancelled)
                        return@Thread
                    }

                    post {
                        onStatus(
                            "얼굴 추적 분석 중… " +
                                (index + 1) +
                                "/" +
                                sampleTimes.size
                        )
                    }

                    var decoded: Bitmap? = null
                    var oriented: Bitmap? = null

                    try {
                        decoded = try {
                            retriever.getScaledFrameAtTime(
                                timeMs * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                decodeWidth,
                                decodeHeight
                            )
                        } catch (_: Throwable) {
                            retriever.getFrameAtTime(
                                timeMs * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST
                            )
                        }

                        val source = decoded
                        if (source == null) {
                            return@forEachIndexed
                        }

                        oriented = rotateBitmap(source, rotation)
                        val image = InputImage.fromBitmap(
                            oriented,
                            0
                        )

                        val faces = Tasks.await(
                            detector.process(image)
                        )

                        val selected = selectFace(
                            faces = faces,
                            width = oriented.width,
                            height = oriented.height,
                            preferredTrackingId = preferredTrackingId,
                            targetX = previousX,
                            targetY = previousY
                        )

                        if (selected != null) {
                            selected.trackingId?.let {
                                preferredTrackingId = it
                            }

                            val box = selected.boundingBox
                            val rawX = (
                                box.exactCenterX() /
                                    oriented.width.toFloat()
                                ).coerceIn(0f, 1f)
                            val rawY = (
                                box.exactCenterY() /
                                    oriented.height.toFloat()
                                ).coerceIn(0f, 1f)

                            // Low-pass smoothing prevents crop jitter.
                            if (!hasPoint) {
                                smoothedX = rawX
                                smoothedY = rawY
                                hasPoint = true
                            } else {
                                val alpha = 0.42f
                                smoothedX =
                                    smoothedX * (1f - alpha) +
                                        rawX * alpha
                                smoothedY =
                                    smoothedY * (1f - alpha) +
                                        rawY * alpha
                            }

                            previousX = smoothedX
                            previousY = smoothedY

                            points += FocusKeyframe(
                                timeMs = timeMs,
                                x = smoothedX.coerceIn(0f, 1f),
                                y = smoothedY.coerceIn(0f, 1f)
                            )
                        }
                    } finally {
                        if (
                            oriented != null &&
                            oriented !== decoded &&
                            !oriented.isRecycled
                        ) {
                            oriented.recycle()
                        }
                        if (
                            decoded != null &&
                            !decoded.isRecycled
                        ) {
                            decoded.recycle()
                        }
                    }

                    post {
                        onProgress(
                            ((index + 1f) / sampleTimes.size)
                                .coerceIn(0f, 1f)
                        )
                    }
                }

                if (cancelled.get()) {
                    post(onCancelled)
                    return@Thread
                }

                val completed = ensureBoundaryPoints(
                    points = points,
                    startMs = clipStart,
                    endMs = clipEnd,
                    fallbackX = initialFocusX,
                    fallbackY = initialFocusY
                )

                post { onComplete(completed) }
            } catch (e: Throwable) {
                if (cancelled.get()) {
                    post(onCancelled)
                } else {
                    post {
                        onError(
                            "얼굴 추적 분석에 실패했습니다. " +
                                (e.message ?: e.javaClass.simpleName)
                        )
                    }
                }
            } finally {
                runCatching { retriever.release() }
                runCatching { detector.close() }
            }
        }.start()
    }

    private fun selectFace(
        faces: List<Face>,
        width: Int,
        height: Int,
        preferredTrackingId: Int?,
        targetX: Float,
        targetY: Float
    ): Face? {
        if (faces.isEmpty()) return null

        preferredTrackingId?.let { id ->
            faces.firstOrNull {
                it.trackingId == id
            }?.let {
                return it
            }
        }

        return faces.minByOrNull { face ->
            val box = face.boundingBox
            val cx =
                box.exactCenterX() /
                    width.coerceAtLeast(1).toFloat()
            val cy =
                box.exactCenterY() /
                    height.coerceAtLeast(1).toFloat()

            val dx = cx - targetX
            val dy = cy - targetY
            val distance =
                dx * dx +
                    dy * dy

            val area =
                (
                    box.width().coerceAtLeast(1) *
                        box.height().coerceAtLeast(1)
                    ).toFloat() /
                    (
                        width.coerceAtLeast(1) *
                            height.coerceAtLeast(1)
                        ).toFloat()

            // Prefer the face nearest the user's tapped focus,
            // with a small bias toward a larger/clearer face.
            distance - area * 0.18f
        }
    }

    private fun ensureBoundaryPoints(
        points: List<FocusKeyframe>,
        startMs: Long,
        endMs: Long,
        fallbackX: Float,
        fallbackY: Float
    ): List<FocusKeyframe> {
        if (points.isEmpty()) {
            return emptyList()
        }

        val sorted = points
            .sortedBy { it.timeMs }
            .toMutableList()

        if (sorted.first().timeMs > startMs) {
            sorted.add(
                0,
                FocusKeyframe(
                    startMs,
                    sorted.first().x,
                    sorted.first().y
                )
            )
        }

        if (sorted.last().timeMs < endMs) {
            sorted += FocusKeyframe(
                endMs,
                sorted.last().x,
                sorted.last().y
            )
        }

        return sorted.ifEmpty {
            listOf(
                FocusKeyframe(
                    startMs,
                    fallbackX.coerceIn(0f, 1f),
                    fallbackY.coerceIn(0f, 1f)
                )
            )
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun rotateBitmap(
        source: Bitmap,
        rotation: Int
    ): Bitmap {
        if (rotation % 360 == 0) {
            return source
        }

        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true
        )
    }
}
