package com.stickermaker.whatsapp.domain

object WhatsAppLimits {
  const val STICKER_DIMENSION_PX = 512
  const val MAX_STICKER_BYTES = 100 * 1024
  const val TRAY_ICON_DIMENSION_PX = 96
  const val MAX_TRAY_ICON_BYTES = 50 * 1024
  const val MIN_STICKERS_PER_PACK = 3
  const val MAX_STICKERS_PER_PACK = 30
  const val MAX_TEXT_LENGTH = 128
  const val MIN_EMOJIS = 1
  const val MAX_EMOJIS = 3
  const val MAX_A11Y_TEXT_LENGTH = 125
  const val DEFAULT_TRAY_FILE_NAME = "tray.png"
  val DEFAULT_STICKER_EMOJIS: List<String> = listOf("🙂")
}

object WhatsAppContract {
  const val PATH_METADATA = "metadata"
  const val PATH_STICKERS = "stickers"
  const val PATH_STICKERS_ASSET = "stickers_asset"

  const val COL_PACK_IDENTIFIER = "sticker_pack_identifier"
  const val COL_PACK_NAME = "sticker_pack_name"
  const val COL_PACK_PUBLISHER = "sticker_pack_publisher"
  const val COL_PACK_ICON = "sticker_pack_icon"
  const val COL_ANDROID_PLAY_STORE_LINK = "android_play_store_link"
  const val COL_IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
  const val COL_PUBLISHER_EMAIL = "sticker_pack_publisher_email"
  const val COL_PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
  const val COL_PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
  const val COL_LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
  const val COL_IMAGE_DATA_VERSION = "image_data_version"
  const val COL_AVOID_CACHE = "whatsapp_will_not_cache_stickers"
  const val COL_ANIMATED_STICKER_PACK = "animated_sticker_pack"

  val METADATA_COLUMNS: Array<String> = arrayOf(
    COL_PACK_IDENTIFIER,
    COL_PACK_NAME,
    COL_PACK_PUBLISHER,
    COL_PACK_ICON,
    COL_ANDROID_PLAY_STORE_LINK,
    COL_IOS_APP_DOWNLOAD_LINK,
    COL_PUBLISHER_EMAIL,
    COL_PUBLISHER_WEBSITE,
    COL_PRIVACY_POLICY_WEBSITE,
    COL_LICENSE_AGREEMENT_WEBSITE,
    COL_IMAGE_DATA_VERSION,
    COL_AVOID_CACHE,
    COL_ANIMATED_STICKER_PACK,
  )

  const val COL_STICKER_FILE_NAME = "sticker_file_name"
  const val COL_STICKER_EMOJI = "sticker_emoji"
  const val COL_STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"

  val STICKER_COLUMNS: Array<String> = arrayOf(
    COL_STICKER_FILE_NAME,
    COL_STICKER_EMOJI,
    COL_STICKER_ACCESSIBILITY_TEXT,
  )

  const val MIME_WEBP = "image/webp"
  const val MIME_PNG = "image/png"

  fun metadataDirMime(authority: String): String =
    "vnd.android.cursor.dir/vnd.$authority.$PATH_METADATA"

  fun metadataItemMime(authority: String): String =
    "vnd.android.cursor.item/vnd.$authority.$PATH_METADATA"

  fun stickersDirMime(authority: String): String =
    "vnd.android.cursor.dir/vnd.$authority.$PATH_STICKERS"

  const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
  const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
  const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
  const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
  const val EXTRA_VALIDATION_ERROR = "validation_error"

  const val WHATSAPP_CONSUMER_PACKAGE = "com.whatsapp"
  const val WHATSAPP_SMB_PACKAGE = "com.whatsapp.w4b"
  const val WHITELIST_PROVIDER_SUFFIX = ".provider.sticker_whitelist_check"
  const val WHITELIST_QUERY_PATH = "is_whitelisted"
  const val WHITELIST_QUERY_PARAM_AUTHORITY = "authority"
  const val WHITELIST_QUERY_PARAM_IDENTIFIER = "identifier"
  const val WHITELIST_RESULT_COLUMN = "result"
}
