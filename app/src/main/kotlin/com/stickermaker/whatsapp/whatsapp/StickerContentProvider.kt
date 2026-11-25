package com.stickermaker.whatsapp.whatsapp

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stickermaker.whatsapp.BuildConfig
import com.stickermaker.whatsapp.data.FileStickerPackStore
import com.stickermaker.whatsapp.domain.PackId
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppContract

class StickerContentProvider : ContentProvider() {
  private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)
  private lateinit var authority: String
  private lateinit var store: FileStickerPackStore

  override fun onCreate(): Boolean {
    val ctx = context ?: return false
    authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
    store = FileStickerPackStore.forContext(ctx)

    uriMatcher.addURI(authority, WhatsAppContract.PATH_METADATA, CODE_METADATA)
    uriMatcher.addURI(authority, "${WhatsAppContract.PATH_METADATA}/*", CODE_METADATA_SINGLE)
    uriMatcher.addURI(authority, "${WhatsAppContract.PATH_STICKERS}/*", CODE_STICKERS)
    uriMatcher.addURI(authority, "${WhatsAppContract.PATH_STICKERS_ASSET}/*/*", CODE_STICKERS_ASSET)
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor {
    val cursor = when (uriMatcher.match(uri)) {
      CODE_METADATA -> buildMetadataCursor(store.listPacks())
      CODE_METADATA_SINGLE -> {
        val pack = uri.lastPathSegment
          ?.let(PackId::parseOrNull)
          ?.let(store::getPack)
        buildMetadataCursor(listOfNotNull(pack))
      }
      CODE_STICKERS -> {
        val pack = uri.lastPathSegment
          ?.let(PackId::parseOrNull)
          ?.let(store::getPack)
        buildStickersCursor(pack)
      }
      else -> throw IllegalArgumentException("Unknown URI: $uri")
    }
    context?.contentResolver?.let { resolver ->
      cursor.setNotificationUri(resolver, uri)
    }
    return cursor
  }

  override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
    CODE_METADATA -> WhatsAppContract.metadataDirMime(authority)
    CODE_METADATA_SINGLE -> WhatsAppContract.metadataItemMime(authority)
    CODE_STICKERS -> WhatsAppContract.stickersDirMime(authority)
    CODE_STICKERS_ASSET -> {
      val segments = uri.pathSegments
      if (segments.size == 3) {
        store.resolveAssetFile(segments[1], segments[2])?.mimeType
      } else {
        null
      }
    }
    else -> throw IllegalArgumentException("Unknown URI: $uri")
  }

  override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
    if (mode != "r") {
      throw UnsupportedOperationException("Write mode not supported: $mode")
    }
    if (uriMatcher.match(uri) != CODE_STICKERS_ASSET) {
      return null
    }
    val segments = uri.pathSegments
    if (segments.size != 3) {
      return null
    }
    val resolved = store.resolveAssetFile(segments[1], segments[2]) ?: return null
    val pfd = ParcelFileDescriptor.open(resolved.file, ParcelFileDescriptor.MODE_READ_ONLY)
    return AssetFileDescriptor(pfd, 0L, resolved.file.length())
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? {
    throw UnsupportedOperationException("Read-only content provider")
  }

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
    throw UnsupportedOperationException("Read-only content provider")
  }

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int {
    throw UnsupportedOperationException("Read-only content provider")
  }

  private fun buildMetadataCursor(packs: List<StickerPack>): MatrixCursor {
    val cursor = MatrixCursor(WhatsAppContract.METADATA_COLUMNS, packs.size)
    for (pack in packs) {
      cursor.addRow(
        arrayOf<Any>(
          pack.id,
          pack.name,
          pack.publisher,
          pack.trayImageFile,
          "",
          "",
          "",
          "",
          "",
          "",
          pack.imageDataVersion.toString(),
          0,
          0,
        ),
      )
    }
    return cursor
  }

  private fun buildStickersCursor(pack: StickerPack?): MatrixCursor {
    val stickers = pack?.stickers.orEmpty()
    val cursor = MatrixCursor(WhatsAppContract.STICKER_COLUMNS, stickers.size)
    for (sticker in stickers) {
      cursor.addRow(
        arrayOf<Any>(
          sticker.fileName,
          sticker.emojis.joinToString(","),
          sticker.accessibilityText,
        ),
      )
    }
    return cursor
  }

  companion object {
    private const val CODE_METADATA = 1
    private const val CODE_METADATA_SINGLE = 2
    private const val CODE_STICKERS = 3
    private const val CODE_STICKERS_ASSET = 4

    fun metadataUri(authority: String = BuildConfig.CONTENT_PROVIDER_AUTHORITY): Uri =
      Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority)
        .appendPath(WhatsAppContract.PATH_METADATA)
        .build()

    fun notifyPacksChanged(
      context: Context,
      authority: String = BuildConfig.CONTENT_PROVIDER_AUTHORITY,
    ) {
      context.contentResolver.notifyChange(metadataUri(authority), null)
    }
  }
}
