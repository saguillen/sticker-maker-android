package com.stickermaker.whatsapp.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.stickermaker.whatsapp.domain.EncodeResult
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import com.stickermaker.whatsapp.domain.computeFitInsideRect
import com.stickermaker.whatsapp.domain.encodeWithinBudget
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

data class ConvertedSticker(
  val webpBytes: ByteArray,
  val trayPngBytes: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ConvertedSticker) return false
    return webpBytes.contentEquals(other.webpBytes) && trayPngBytes.contentEquals(other.trayPngBytes)
  }

  override fun hashCode(): Int =
    31 * webpBytes.contentHashCode() + trayPngBytes.contentHashCode()
}

sealed interface ConversionError {
  data object UnreadableSource : ConversionError
  data class ExceedsMaxSize(val smallestBytes: Int, val maxBytes: Int) : ConversionError
}

sealed interface ConversionOutcome {
  data class Success(val converted: ConvertedSticker) : ConversionOutcome
  data class Failure(val error: ConversionError) : ConversionOutcome
}

sealed interface TrayConversionOutcome {
  data class Success(val trayPngBytes: ByteArray) : TrayConversionOutcome {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Success) return false
      return trayPngBytes.contentEquals(other.trayPngBytes)
    }

    override fun hashCode(): Int = trayPngBytes.contentHashCode()
  }

  data class Failure(val error: ConversionError) : TrayConversionOutcome
}

class AndroidStickerImageConverter(
  private val contentResolver: ContentResolver,
) {
  fun convert(uri: Uri): ConversionOutcome {
    val oriented = decodeOrientedBitmap(uri)
      ?: return ConversionOutcome.Failure(ConversionError.UnreadableSource)
    return try {
      convertBitmap(oriented)
    } finally {
      oriented.recycle()
    }
  }

  fun convertTrayImage(uri: Uri): TrayConversionOutcome {
    val oriented = decodeOrientedBitmap(uri)
      ?: return TrayConversionOutcome.Failure(ConversionError.UnreadableSource)
    return try {
      convertTrayBitmap(oriented)
    } finally {
      oriented.recycle()
    }
  }

  fun convertTrayFile(file: File): TrayConversionOutcome {
    if (!file.isFile) {
      return TrayConversionOutcome.Failure(ConversionError.UnreadableSource)
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath)
      ?: return TrayConversionOutcome.Failure(ConversionError.UnreadableSource)
    return try {
      convertTrayBitmap(decoded)
    } finally {
      decoded.recycle()
    }
  }

  fun convertTrayBitmap(source: Bitmap): TrayConversionOutcome {
    if (source.width <= 0 || source.height <= 0 || source.isRecycled) {
      return TrayConversionOutcome.Failure(ConversionError.UnreadableSource)
    }
    val trayBytes = encodeTrayPng(source)
    return when {
      trayBytes.size < MIN_PNG_BYTES -> TrayConversionOutcome.Failure(ConversionError.UnreadableSource)
      trayBytes.size > WhatsAppLimits.MAX_TRAY_ICON_BYTES -> TrayConversionOutcome.Failure(
        ConversionError.ExceedsMaxSize(
          smallestBytes = trayBytes.size,
          maxBytes = WhatsAppLimits.MAX_TRAY_ICON_BYTES,
        ),
      )
      else -> TrayConversionOutcome.Success(trayBytes)
    }
  }

  fun convertBitmap(source: Bitmap): ConversionOutcome {
    if (source.width <= 0 || source.height <= 0 || source.isRecycled) {
      return ConversionOutcome.Failure(ConversionError.UnreadableSource)
    }

    val stickerBitmap = renderCenteredSquare(source, WhatsAppLimits.STICKER_DIMENSION_PX)
    return try {
      val webpFormat = webpCompressFormat()
      val encodeResult = encodeWithinBudget(maxBytes = WhatsAppLimits.MAX_STICKER_BYTES) { quality ->
        compressBitmap(stickerBitmap, webpFormat, quality)
      }

      when (encodeResult) {
        is EncodeResult.ExceedsBudget -> ConversionOutcome.Failure(
          ConversionError.ExceedsMaxSize(
            smallestBytes = encodeResult.smallestBytes,
            maxBytes = encodeResult.maxBytes,
          ),
        )
        is EncodeResult.Success -> when (val trayOutcome = convertTrayBitmap(source)) {
          is TrayConversionOutcome.Success -> ConversionOutcome.Success(
            ConvertedSticker(
              webpBytes = encodeResult.bytes,
              trayPngBytes = trayOutcome.trayPngBytes,
            ),
          )
          is TrayConversionOutcome.Failure -> ConversionOutcome.Failure(trayOutcome.error)
        }
      }
    } finally {
      stickerBitmap.recycle()
    }
  }

  internal fun decodeOrientedBitmap(uri: Uri): Bitmap? {
    val bounds = decodeBounds(uri) ?: return null
    val (srcWidth, srcHeight) = bounds
    val orientation = readExifOrientation(uri)
    val sampleSize = computeInSampleSize(srcWidth, srcHeight)

    val decoded = decodeSampledBitmap(uri, sampleSize) ?: return null
    val oriented = applyExifOrientation(decoded, orientation)
    if (oriented !== decoded) {
      decoded.recycle()
    }
    return oriented
  }

  private fun decodeBounds(uri: Uri): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    return try {
      contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
      } ?: return null
      if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth to options.outHeight
      } else {
        null
      }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  private fun readExifOrientation(uri: Uri): Int = try {
    contentResolver.openInputStream(uri)?.use { stream ->
      ExifInterface(stream).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
      )
    } ?: ExifInterface.ORIENTATION_NORMAL
  } catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
  } catch (_: SecurityException) {
    ExifInterface.ORIENTATION_NORMAL
  }

  private fun decodeSampledBitmap(uri: Uri, sampleSize: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return try {
      contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
      }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.setRotate(180f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.setRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.setRotate(-90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
      else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }

  private fun renderCenteredSquare(source: Bitmap, targetSize: Int): Bitmap {
    val canvasBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val fit = computeFitInsideRect(source.width, source.height, targetSize)
    val dest = Rect(fit.left, fit.top, fit.left + fit.width, fit.top + fit.height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    Canvas(canvasBitmap).drawBitmap(source, null, dest, paint)
    return canvasBitmap
  }

  private fun encodeTrayPng(source: Bitmap): ByteArray {
    var fallback = ByteArray(0)
    for (dimension in TRAY_DIMENSION_CANDIDATES) {
      val trayBitmap = renderCenteredSquare(source, dimension)
      val bytes = try {
        compressBitmap(trayBitmap, Bitmap.CompressFormat.PNG, 100)
      } finally {
        trayBitmap.recycle()
      }
      fallback = bytes
      if (bytes.size <= WhatsAppLimits.MAX_TRAY_ICON_BYTES) {
        return bytes
      }
    }
    return fallback
  }

  private fun compressBitmap(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
    val output = ByteArrayOutputStream()
    bitmap.compress(format, quality, output)
    return output.toByteArray()
  }

  private fun webpCompressFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Bitmap.CompressFormat.WEBP_LOSSY
    } else {
      @Suppress("DEPRECATION")
      Bitmap.CompressFormat.WEBP
    }

  private fun computeInSampleSize(srcWidth: Int, srcHeight: Int): Int {
    var sampleSize = 1
    val maxEdge = maxOf(srcWidth, srcHeight)
    while (maxEdge / (sampleSize * 2) >= TARGET_DECODE_MIN_EDGE &&
      maxEdge / sampleSize > TARGET_DECODE_MAX_EDGE
    ) {
      sampleSize *= 2
    }
    return sampleSize
  }

  private companion object {
    const val MIN_PNG_BYTES = 8
    const val TARGET_DECODE_MIN_EDGE = 512
    const val TARGET_DECODE_MAX_EDGE = 1024
    val TRAY_DIMENSION_CANDIDATES = intArrayOf(WhatsAppLimits.TRAY_ICON_DIMENSION_PX, 72, 48)
  }
}
