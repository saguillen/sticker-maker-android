package com.stickermaker.whatsapp.domain

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
}

sealed interface PackReadiness {
  data object Ready : PackReadiness
  data class NotReady(val issues: List<PackValidationIssue>) : PackReadiness
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
  }

  return if (issues.isEmpty()) PackReadiness.Ready else PackReadiness.NotReady(issues)
}
