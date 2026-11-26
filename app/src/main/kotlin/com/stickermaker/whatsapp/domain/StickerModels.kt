package com.stickermaker.whatsapp.domain

import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable

@JvmInline
value class PackId(val value: String) {
  init {
    require(value.isNotBlank()) { "PackId must not be blank" }
    require(value.length <= WhatsAppLimits.MAX_TEXT_LENGTH) {
      "PackId exceeds ${WhatsAppLimits.MAX_TEXT_LENGTH} characters"
    }
    require(value != "." && !value.contains("..")) {
      "PackId must not contain path traversal segments"
    }
    require(VALID_PATTERN.matches(value)) {
      "PackId contains invalid characters: $value"
    }
  }

  companion object {
    private val VALID_PATTERN = Regex("^[a-zA-Z0-9_.\\- ]+$")

    fun parseOrNull(raw: String): PackId? = runCatching { PackId(raw) }.getOrNull()

    fun generate(): PackId {
      val hex = UUID.randomUUID().toString().replace("-", "").take(12)
      return PackId("pack_$hex")
    }
  }
}

@JvmInline
value class StickerFileName(val value: String) {
  init {
    require(value.isNotBlank()) { "StickerFileName must not be blank" }
    require(!value.contains('/') && !value.contains('\\') && !value.contains("..")) {
      "StickerFileName must be a single safe filename: $value"
    }
    require(value.endsWith(".webp") || value.endsWith(".png")) {
      "StickerFileName must end with .webp or .png: $value"
    }
    require(value.substringBeforeLast('.').isNotEmpty()) {
      "StickerFileName must have a non-empty base name: $value"
    }
  }

  val mimeType: String
    get() = if (value.endsWith(".png")) WhatsAppContract.MIME_PNG else WhatsAppContract.MIME_WEBP

  companion object {
    fun parseOrNull(raw: String): StickerFileName? = runCatching { StickerFileName(raw) }.getOrNull()

    fun generateWebp(): StickerFileName {
      val hex = UUID.randomUUID().toString().replace("-", "").take(12)
      return StickerFileName("sticker_$hex.webp")
    }
  }
}

@Serializable
data class StickerItem(
  val fileName: String,
  val emojis: List<String> = WhatsAppLimits.DEFAULT_STICKER_EMOJIS,
  val sizeBytes: Long,
  val accessibilityText: String = "",
)

@Serializable
data class StickerPack(
  val id: String,
  val name: String,
  val publisher: String,
  val trayImageFile: String = WhatsAppLimits.DEFAULT_TRAY_FILE_NAME,
  val imageDataVersion: Int = 1,
  val stickers: List<StickerItem> = emptyList(),
)

sealed interface PackValidationIssue {
  data object BlankName : PackValidationIssue
  data object BlankPublisher : PackValidationIssue
  data object NameTooLong : PackValidationIssue
  data object PublisherTooLong : PackValidationIssue
  data object MissingTrayIcon : PackValidationIssue
  data class TooFewStickers(val current: Int, val required: Int) : PackValidationIssue
  data class TooManyStickers(val current: Int, val max: Int) : PackValidationIssue
  data class StickerExceedsMaxSize(val fileName: String, val sizeBytes: Long) : PackValidationIssue
  data class InvalidStickerEmojis(val fileName: String, val count: Int) : PackValidationIssue
}

sealed interface PackReadiness {
  data object Ready : PackReadiness
  data class NotReady(val issues: List<PackValidationIssue>) : PackReadiness
}

sealed interface EmojiValidationError {
  data object Empty : EmojiValidationError
  data class TooMany(val count: Int, val max: Int) : EmojiValidationError
  data object NonEmojiInput : EmojiValidationError
}

sealed interface EmojiParseResult {
  data class Valid(val emojis: List<String>) : EmojiParseResult
  data class Invalid(val error: EmojiValidationError) : EmojiParseResult
}

private val EMOJI_SPLIT_REGEX = Regex("[\\s,]+")
private const val ZERO_WIDTH_JOINER = 0x200D
private const val VARIATION_SELECTOR_TEXT = 0xFE0E
private const val VARIATION_SELECTOR_EMOJI = 0xFE0F
private const val KEYCAP_COMBINING_MARK = 0x20E3

fun parseStickerEmojis(raw: String): EmojiParseResult {
  val chunks = raw.split(EMOJI_SPLIT_REGEX).filter { it.isNotEmpty() }
  if (chunks.isEmpty()) {
    return EmojiParseResult.Invalid(EmojiValidationError.Empty)
  }

  val clusters = mutableListOf<String>()
  for (chunk in chunks) {
    clusters += segmentGraphemeClusters(chunk)
  }

  if (clusters.isEmpty()) {
    return EmojiParseResult.Invalid(EmojiValidationError.Empty)
  }

  if (clusters.any { !isEmojiCluster(it) }) {
    return EmojiParseResult.Invalid(EmojiValidationError.NonEmojiInput)
  }

  if (clusters.size > WhatsAppLimits.MAX_EMOJIS) {
    return EmojiParseResult.Invalid(
      EmojiValidationError.TooMany(count = clusters.size, max = WhatsAppLimits.MAX_EMOJIS),
    )
  }

  return EmojiParseResult.Valid(clusters)
}

private fun segmentGraphemeClusters(chunk: String): List<String> {
  val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
  iterator.setText(chunk)

  val rawSegments = mutableListOf<String>()
  var start = iterator.first()
  var end = iterator.next()
  while (end != BreakIterator.DONE) {
    rawSegments += chunk.substring(start, end)
    start = end
    end = iterator.next()
  }

  val merged = mutableListOf<String>()
  for (segment in rawSegments) {
    if (merged.isNotEmpty() && shouldMergeWithPrevious(merged.last(), segment)) {
      merged[merged.lastIndex] = merged.last() + segment
    } else {
      merged += segment
    }
  }
  return merged
}

private fun shouldMergeWithPrevious(previous: String, current: String): Boolean {
  val prevLastCp = previous.codePointBefore(previous.length)
  val currFirstCp = current.codePointAt(0)
  if (prevLastCp == ZERO_WIDTH_JOINER) return true
  if (currFirstCp == ZERO_WIDTH_JOINER) return true
  if (currFirstCp == VARIATION_SELECTOR_TEXT || currFirstCp == VARIATION_SELECTOR_EMOJI) return true
  if (currFirstCp == KEYCAP_COMBINING_MARK) return true
  if (currFirstCp in 0x1F3FB..0x1F3FF) return true
  if (currFirstCp in 0xE0020..0xE007F) return true
  if (isSingleRegionalIndicator(previous) && isSingleRegionalIndicator(current)) return true
  return false
}

private fun isSingleRegionalIndicator(segment: String): Boolean =
  segment.codePointCount(0, segment.length) == 1 && segment.codePointAt(0) in 0x1F1E6..0x1F1FF

private fun isEmojiCluster(cluster: String): Boolean {
  val codePoints = cluster.codePoints().toArray()
  if (codePoints.isEmpty()) return false
  if (codePoints.contains(KEYCAP_COMBINING_MARK)) {
    val base = codePoints[0]
    return base in '0'.code..'9'.code || base == '#'.code || base == '*'.code
  }
  return codePoints.any(::isEmojiCodePoint)
}

private fun isEmojiCodePoint(cp: Int): Boolean = when (cp) {
  in 0x1F000..0x1FAFF,
  in 0x2600..0x27BF,
  in 0x2300..0x23FF,
  in 0x2B00..0x2BFF,
  in 0x2194..0x21AA,
  in 0x25AA..0x25FE,
  0x00A9,
  0x00AE,
  0x203C,
  0x2049,
  0x2122,
  0x2139,
  0x24C2,
  0x2934,
  0x2935,
  0x3030,
  0x303D,
  0x3297,
  0x3299,
  -> true
  else -> false
}

fun evaluatePackReadiness(pack: StickerPack, trayExists: Boolean): PackReadiness {
  val issues = mutableListOf<PackValidationIssue>()

  if (pack.name.isBlank()) {
    issues += PackValidationIssue.BlankName
  } else if (pack.name.length > WhatsAppLimits.MAX_TEXT_LENGTH) {
    issues += PackValidationIssue.NameTooLong
  }

  if (pack.publisher.isBlank()) {
    issues += PackValidationIssue.BlankPublisher
  } else if (pack.publisher.length > WhatsAppLimits.MAX_TEXT_LENGTH) {
    issues += PackValidationIssue.PublisherTooLong
  }

  if (!trayExists) {
    issues += PackValidationIssue.MissingTrayIcon
  }

  if (pack.stickers.size < WhatsAppLimits.MIN_STICKERS_PER_PACK) {
    issues += PackValidationIssue.TooFewStickers(
      current = pack.stickers.size,
      required = WhatsAppLimits.MIN_STICKERS_PER_PACK,
    )
  } else if (pack.stickers.size > WhatsAppLimits.MAX_STICKERS_PER_PACK) {
    issues += PackValidationIssue.TooManyStickers(
      current = pack.stickers.size,
      max = WhatsAppLimits.MAX_STICKERS_PER_PACK,
    )
  }

  for (sticker in pack.stickers) {
    if (sticker.sizeBytes > WhatsAppLimits.MAX_STICKER_BYTES) {
      issues += PackValidationIssue.StickerExceedsMaxSize(
        fileName = sticker.fileName,
        sizeBytes = sticker.sizeBytes,
      )
    }
    if (sticker.emojis.size !in WhatsAppLimits.MIN_EMOJIS..WhatsAppLimits.MAX_EMOJIS) {
      issues += PackValidationIssue.InvalidStickerEmojis(
        fileName = sticker.fileName,
        count = sticker.emojis.size,
      )
    }
  }

  return if (issues.isEmpty()) PackReadiness.Ready else PackReadiness.NotReady(issues)
}
