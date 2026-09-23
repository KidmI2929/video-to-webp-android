package com.kidmi.videotowebp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

fun mergeTrackingKeyframes(
    automatic: List<FocusKeyframe>,
    manual: List<FocusKeyframe>,
    startMs: Long,
    endMs: Long
): List<FocusKeyframe> {
    val safeManual = manual
        .filter {
            it.timeMs in startMs..endMs
        }
        .sortedBy { it.timeMs }

    if (safeManual.isEmpty()) {
        return automatic
            .filter { it.timeMs in startMs..endMs }
            .sortedBy { it.timeMs }
    }

    val windowMs = 450L
    val merged = automatic
        .filter { auto ->
            auto.timeMs in startMs..endMs &&
                safeManual.none {
                    abs(it.timeMs - auto.timeMs) <= windowMs
                }
        }
        .toMutableList()

    safeManual.forEach { point ->
        merged += FocusKeyframe(
            timeMs = (point.timeMs - 180L)
                .coerceAtLeast(startMs),
            x = point.x,
            y = point.y
        )
        merged += point
        merged += FocusKeyframe(
            timeMs = (point.timeMs + 180L)
                .coerceAtMost(endMs),
            x = point.x,
            y = point.y
        )
    }

    val sorted = merged
        .sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .toMutableList()

    if (sorted.isNotEmpty()) {
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
    }

    return sorted
}

data class OutputEstimate(
    val sizeBytes: Long,
    val seconds: Double
)

fun estimateOutput(
    info: VideoInfo,
    durationMs: Long,
    fps: Int,
    quality: Int,
    maxSide: Int?,
    lossless: Boolean,
    cropZoom: Float,
    speed: ConversionSpeed
): OutputEstimate {
    val durationSec =
        durationMs.coerceAtLeast(100L) / 1000.0
    val sourcePixels =
        max(1, info.width) * max(1, info.height)

    val scale = maxSide?.let { side ->
        min(
            1.0,
            side.toDouble() /
                max(info.width, info.height)
                    .coerceAtLeast(1)
        )
    } ?: 1.0

    val zoom = cropZoom.coerceIn(1f, 4f)
    val effectivePixels =
        sourcePixels *
            scale.pow(2.0) /
            zoom.toDouble().pow(2.0)

    val q =
        quality.coerceIn(10, 100) / 100.0

    val bytesPerPixelFrame = if (lossless) {
        0.18
    } else {
        0.018 + 0.065 * q.pow(1.8)
    }

    val size =
        (
            effectivePixels *
                fps.coerceIn(1, 30) *
                durationSec *
                bytesPerPixelFrame
            ).toLong()
            .coerceAtLeast(1024L)

    val speedFactor = when (speed) {
        ConversionSpeed.TURBO -> 2.8
        ConversionSpeed.FAST -> 2.0
        ConversionSpeed.BALANCED -> 1.15
        ConversionSpeed.MAX_COMPRESSION -> 0.65
    }

    val pixelFactor =
        effectivePixels /
            (1280.0 * 720.0)

    val seconds =
        (
            durationSec *
                pixelFactor *
                fps.coerceIn(1, 30) / 15.0 /
                speedFactor
            )
            .coerceAtLeast(0.5)

    return OutputEstimate(
        sizeBytes = size,
        seconds = seconds
    )
}

fun recommendedQualityForTarget(
    estimateAtCurrentQuality: Long,
    currentQuality: Int,
    targetBytes: Long
): Int {
    if (estimateAtCurrentQuality <= 0L ||
        targetBytes <= 0L
    ) {
        return currentQuality
    }

    val ratio =
        targetBytes.toDouble() /
            estimateAtCurrentQuality.toDouble()

    return (
        currentQuality *
            ratio.pow(0.55)
        )
        .toInt()
        .coerceIn(10, 100)
}
