package com.stickermaker.whatsapp.domain

import kotlin.math.min
import kotlin.math.roundToInt

data class FitRect(
  val left: Int,
  val top: Int,
  val width: Int,
  val height: Int,
)

fun computeFitInsideRect(srcWidth: Int, srcHeight: Int, targetSize: Int): FitRect {
  require(srcWidth > 0) { "srcWidth must be positive: $srcWidth" }
  require(srcHeight > 0) { "srcHeight must be positive: $srcHeight" }
  require(targetSize > 0) { "targetSize must be positive: $targetSize" }

  val scale = min(targetSize.toDouble() / srcWidth, targetSize.toDouble() / srcHeight)
  val width = (srcWidth * scale).roundToInt().coerceIn(1, targetSize)
  val height = (srcHeight * scale).roundToInt().coerceIn(1, targetSize)
  val left = (targetSize - width) / 2
  val top = (targetSize - height) / 2
  return FitRect(left = left, top = top, width = width, height = height)
}

sealed interface EncodeResult {
  data class Success(val bytes: ByteArray, val quality: Int) : EncodeResult {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Success) return false
      return quality == other.quality && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + quality
  }

  data class ExceedsBudget(val smallestBytes: Int, val maxBytes: Int) : EncodeResult
}

fun encodeWithinBudget(
  maxBytes: Int,
  minQuality: Int = 5,
  maxQuality: Int = 90,
  encodeAtQuality: (Int) -> ByteArray,
): EncodeResult {
  require(maxBytes > 0) { "maxBytes must be positive: $maxBytes" }
  require(minQuality in 0..maxQuality) { "Invalid quality range: $minQuality..$maxQuality" }

  val highestAttempt = encodeAtQuality(maxQuality)
  if (highestAttempt.size <= maxBytes) {
    return EncodeResult.Success(bytes = highestAttempt, quality = maxQuality)
  }
  if (minQuality == maxQuality) {
    return EncodeResult.ExceedsBudget(smallestBytes = highestAttempt.size, maxBytes = maxBytes)
  }

  var low = minQuality
  var high = maxQuality - 1
  var bestBytes: ByteArray? = null
  var bestQuality = -1
  var smallestOverBudget = highestAttempt.size

  while (low <= high) {
    val mid = (low + high) ushr 1
    val candidate = encodeAtQuality(mid)
    if (candidate.size <= maxBytes) {
      bestBytes = candidate
      bestQuality = mid
      low = mid + 1
    } else {
      if (candidate.size < smallestOverBudget) {
        smallestOverBudget = candidate.size
      }
      high = mid - 1
    }
  }

  return if (bestBytes != null) {
    EncodeResult.Success(bytes = bestBytes, quality = bestQuality)
  } else {
    EncodeResult.ExceedsBudget(smallestBytes = smallestOverBudget, maxBytes = maxBytes)
  }
}
