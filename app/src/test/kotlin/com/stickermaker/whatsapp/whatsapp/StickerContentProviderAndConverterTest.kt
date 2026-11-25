package com.stickermaker.whatsapp.whatsapp

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.stickermaker.whatsapp.BuildConfig
import com.stickermaker.whatsapp.data.AndroidStickerImageConverter
import com.stickermaker.whatsapp.data.ConversionOutcome
import com.stickermaker.whatsapp.data.FileStickerPackStore
import com.stickermaker.whatsapp.domain.PackId
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppContract
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import java.io.FileInputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerContentProviderAndConverterTest {

  @Test
  fun converterProduces512WebpWithinBudgetAnd96PngTrayFromHighResNonSquareBitmap() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val converter = AndroidStickerImageConverter(context.contentResolver)

    val source = createColorfulNoisyBitmap(width = 1200, height = 800)
    val outcome = try {
      converter.convertBitmap(source)
    } finally {
      source.recycle()
    }

    val success = outcome as ConversionOutcome.Success
    val webp = success.converted.webpBytes
    val tray = success.converted.trayPngBytes

    assertTrue("WebP must be <= 100 KB, got ${webp.size}", webp.size in 12..WhatsAppLimits.MAX_STICKER_BYTES)
    assertEquals("RIFF", String(webp, 0, 4, Charsets.US_ASCII))
    assertEquals("WEBP", String(webp, 8, 4, Charsets.US_ASCII))

    val decodedSticker = BitmapFactory.decodeByteArray(webp, 0, webp.size)
    assertNotNull(decodedSticker)
    try {
      assertEquals(WhatsAppLimits.STICKER_DIMENSION_PX, decodedSticker.width)
      assertEquals(WhatsAppLimits.STICKER_DIMENSION_PX, decodedSticker.height)
    } finally {
      decodedSticker.recycle()
    }

    assertTrue("Tray PNG must be <= 50 KB, got ${tray.size}", tray.size in 8..WhatsAppLimits.MAX_TRAY_ICON_BYTES)
    assertArrayEquals(
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
      tray.copyOfRange(0, 8),
    )

    val decodedTray = BitmapFactory.decodeByteArray(tray, 0, tray.size)
    assertNotNull(decodedTray)
    try {
      assertEquals(WhatsAppLimits.TRAY_ICON_DIMENSION_PX, decodedTray.width)
      assertEquals(WhatsAppLimits.TRAY_ICON_DIMENSION_PX, decodedTray.height)
    } finally {
      decodedTray.recycle()
    }
  }

  @Test
  fun contentProviderServesDynamicallyCreatedPacksWithExactWhatsAppContract() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
    val provider = Robolectric.setupContentProvider(StickerContentProvider::class.java, authority)

    val store = FileStickerPackStore.forContext(context)
    val converter = AndroidStickerImageConverter(context.contentResolver)
    val source = createColorfulNoisyBitmap(width = 640, height = 480)
    val converted = try {
      (converter.convertBitmap(source) as ConversionOutcome.Success).converted
    } finally {
      source.recycle()
    }

    val created = store.createPack(name = "Weekend Pack", publisher = "Sticker Studio")
    val pack = store.addStickers(PackId(created.id), listOf(converted, converted, converted))

    val metadataUri = buildContentUri(authority, WhatsAppContract.PATH_METADATA)
    val singleMetadataUri = buildContentUri(authority, WhatsAppContract.PATH_METADATA, pack.id)
    val stickersUri = buildContentUri(authority, WhatsAppContract.PATH_STICKERS, pack.id)
    val trayUri = buildContentUri(authority, WhatsAppContract.PATH_STICKERS_ASSET, pack.id, pack.trayImageFile)
    val firstStickerName = pack.stickers.first().fileName
    val stickerAssetUri = buildContentUri(authority, WhatsAppContract.PATH_STICKERS_ASSET, pack.id, firstStickerName)

    assertEquals(WhatsAppContract.metadataDirMime(authority), provider.getType(metadataUri))
    assertEquals(WhatsAppContract.metadataItemMime(authority), provider.getType(singleMetadataUri))
    assertEquals(WhatsAppContract.stickersDirMime(authority), provider.getType(stickersUri))
    assertEquals(WhatsAppContract.MIME_PNG, provider.getType(trayUri))
    assertEquals(WhatsAppContract.MIME_WEBP, provider.getType(stickerAssetUri))

    provider.query(metadataUri, null, null, null, null).use { cursor ->
      assertArrayEquals(WhatsAppContract.METADATA_COLUMNS, cursor.columnNames)
      assertEquals(1, cursor.count)
      assertTrue(cursor.moveToFirst())
      assertEquals(pack.id, cursor.getString(0))
      assertEquals("Weekend Pack", cursor.getString(1))
      assertEquals("Sticker Studio", cursor.getString(2))
      assertEquals(WhatsAppLimits.DEFAULT_TRAY_FILE_NAME, cursor.getString(3))
      assertEquals("", cursor.getString(4))
      assertEquals("", cursor.getString(5))
      assertEquals("", cursor.getString(6))
      assertEquals("", cursor.getString(7))
      assertEquals("", cursor.getString(8))
      assertEquals("", cursor.getString(9))
      assertEquals(pack.imageDataVersion.toString(), cursor.getString(10))
      assertEquals(0, cursor.getInt(11))
      assertEquals(0, cursor.getInt(12))
    }

    provider.query(singleMetadataUri, null, null, null, null).use { cursor ->
      assertArrayEquals(WhatsAppContract.METADATA_COLUMNS, cursor.columnNames)
      assertEquals(1, cursor.count)
      assertTrue(cursor.moveToFirst())
      assertEquals(pack.id, cursor.getString(0))
    }

    provider.query(stickersUri, null, null, null, null).use { cursor ->
      assertArrayEquals(WhatsAppContract.STICKER_COLUMNS, cursor.columnNames)
      assertEquals(3, cursor.count)
      var index = 0
      while (cursor.moveToNext()) {
        assertEquals(pack.stickers[index].fileName, cursor.getString(0))
        assertEquals("🙂", cursor.getString(1))
        assertEquals("", cursor.getString(2))
        index++
      }
    }

    provider.openAssetFile(trayUri, "r").use { afd ->
      assertNotNull(afd)
      val readBytes = FileInputStream(afd!!.fileDescriptor).use { it.readBytes() }
      assertArrayEquals(converted.trayPngBytes, readBytes)
    }

    provider.openAssetFile(stickerAssetUri, "r").use { afd ->
      assertNotNull(afd)
      val readBytes = FileInputStream(afd!!.fileDescriptor).use { it.readBytes() }
      assertArrayEquals(converted.webpBytes, readBytes)
    }

    val unknownAssetUri = buildContentUri(authority, WhatsAppContract.PATH_STICKERS_ASSET, pack.id, "missing.webp")
    val traversalAssetUri = buildContentUri(authority, WhatsAppContract.PATH_STICKERS_ASSET, pack.id, "../pack.json")
    assertNull(provider.openAssetFile(unknownAssetUri, "r"))
    assertNull(provider.openAssetFile(traversalAssetUri, "r"))
  }

  @Test
  fun whatsAppPackExporterBuildsExactIntentAndParsesActivityResults() {
    val pack = StickerPack(id = "pack_42", name = "My Pack", publisher = "Me")
    val intent = WhatsAppPackExporter.createAddPackIntent(
      pack = pack,
      authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY,
      targetPackage = WhatsAppContract.WHATSAPP_CONSUMER_PACKAGE,
    )

    assertEquals(WhatsAppContract.ACTION_ENABLE_STICKER_PACK, intent.action)
    assertEquals("pack_42", intent.getStringExtra(WhatsAppContract.EXTRA_STICKER_PACK_ID))
    assertEquals(
      BuildConfig.CONTENT_PROVIDER_AUTHORITY,
      intent.getStringExtra(WhatsAppContract.EXTRA_STICKER_PACK_AUTHORITY),
    )
    assertEquals("My Pack", intent.getStringExtra(WhatsAppContract.EXTRA_STICKER_PACK_NAME))
    assertEquals(WhatsAppContract.WHATSAPP_CONSUMER_PACKAGE, intent.`package`)

    assertEquals(
      ExportResult.Added,
      WhatsAppPackExporter.parseActivityResult(Activity.RESULT_OK, null),
    )

    val errorIntent = Intent().putExtra(WhatsAppContract.EXTRA_VALIDATION_ERROR, "Invalid tray icon")
    assertEquals(
      ExportResult.ValidationError("Invalid tray icon"),
      WhatsAppPackExporter.parseActivityResult(Activity.RESULT_CANCELED, errorIntent),
    )

    assertEquals(
      ExportResult.Cancelled,
      WhatsAppPackExporter.parseActivityResult(Activity.RESULT_CANCELED, null),
    )
  }

  private fun buildContentUri(authority: String, vararg segments: String): Uri {
    val builder = Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority(authority)
    for (segment in segments) {
      builder.appendPath(segment)
    }
    return builder.build()
  }

  private fun createColorfulNoisyBitmap(width: Int, height: Int): Bitmap {
    val random = Random(1337)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val r = ((x * 255) / width + random.nextInt(-30, 31)).coerceIn(0, 255)
        val g = ((y * 255) / height + random.nextInt(-30, 31)).coerceIn(0, 255)
        val b = (random.nextInt(0, 256))
        pixels[y * width + x] = Color.argb(255, r, g, b)
      }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }
}
