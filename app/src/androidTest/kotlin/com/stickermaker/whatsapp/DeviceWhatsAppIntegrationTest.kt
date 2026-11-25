package com.stickermaker.whatsapp

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stickermaker.whatsapp.data.AndroidStickerImageConverter
import com.stickermaker.whatsapp.data.ConversionOutcome
import com.stickermaker.whatsapp.data.FileStickerPackStore
import com.stickermaker.whatsapp.data.TrayConversionOutcome
import com.stickermaker.whatsapp.domain.PackId
import com.stickermaker.whatsapp.domain.StickerFileName
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppContract
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import com.stickermaker.whatsapp.whatsapp.WhatsAppPackExporter
import java.io.FileInputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceWhatsAppIntegrationTest {

  @Test
  fun converterProducesValidWebpAndTrayPngForMultipleAspectRatiosOnDevice() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val converter = AndroidStickerImageConverter(context.contentResolver)

    val sizes = listOf(
      1000 to 600,
      600 to 1000,
      800 to 800,
    )

    for ((index, size) in sizes.withIndex()) {
      val (width, height) = size
      val source = createPatternBitmap(width = width, height = height, seed = 100 + index)
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
      assertArrayEquals(PNG_MAGIC, tray.copyOfRange(0, 8))

      val decodedTray = BitmapFactory.decodeByteArray(tray, 0, tray.size)
      assertNotNull(decodedTray)
      try {
        assertEquals(WhatsAppLimits.TRAY_ICON_DIMENSION_PX, decodedTray.width)
        assertEquals(WhatsAppLimits.TRAY_ICON_DIMENSION_PX, decodedTray.height)
      } finally {
        decodedTray.recycle()
      }
    }
  }

  @Test
  fun contentProviderServesUpdatedTrayAndStickerEmojisEndToEnd() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resolver = context.contentResolver
    val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
    val store = FileStickerPackStore.forContext(context)
    val converter = AndroidStickerImageConverter(resolver)

    val convertedStickers = listOf(1000 to 600, 600 to 1000, 800 to 800).mapIndexed { index, (w, h) ->
      val bitmap = createPatternBitmap(width = w, height = h, seed = 200 + index)
      try {
        (converter.convertBitmap(bitmap) as ConversionOutcome.Success).converted
      } finally {
        bitmap.recycle()
      }
    }

    val customTrayBitmap = createPatternBitmap(width = 320, height = 320, seed = 999)
    val customTrayBytes = try {
      (converter.convertTrayBitmap(customTrayBitmap) as TrayConversionOutcome.Success).trayPngBytes
    } finally {
      customTrayBitmap.recycle()
    }

    val created = store.createPack(name = "Device Integration Pack", publisher = "Sticker Maker Test")
    val packId = PackId(created.id)
    try {
      val populated = store.addStickers(packId, convertedStickers)
      val firstStickerName = populated.stickers.first().fileName
      store.updateTrayIcon(packId, customTrayBytes)
      val updatedPack = store.updateStickerEmojis(
        packId,
        StickerFileName(firstStickerName),
        listOf("🔥", "🎉"),
      )

      val metadataUri = buildContentUri(authority, WhatsAppContract.PATH_METADATA)
      val singleMetadataUri = buildContentUri(authority, WhatsAppContract.PATH_METADATA, updatedPack.id)
      val stickersUri = buildContentUri(authority, WhatsAppContract.PATH_STICKERS, updatedPack.id)
      val trayUri = buildContentUri(
        authority,
        WhatsAppContract.PATH_STICKERS_ASSET,
        updatedPack.id,
        updatedPack.trayImageFile,
      )
      val stickerAssetUri = buildContentUri(
        authority,
        WhatsAppContract.PATH_STICKERS_ASSET,
        updatedPack.id,
        firstStickerName,
      )

      assertEquals(WhatsAppContract.metadataDirMime(authority), resolver.getType(metadataUri))
      assertEquals(WhatsAppContract.metadataItemMime(authority), resolver.getType(singleMetadataUri))
      assertEquals(WhatsAppContract.stickersDirMime(authority), resolver.getType(stickersUri))
      assertEquals(WhatsAppContract.MIME_PNG, resolver.getType(trayUri))
      assertEquals(WhatsAppContract.MIME_WEBP, resolver.getType(stickerAssetUri))

      resolver.query(singleMetadataUri, null, null, null, null).use { cursor ->
        assertNotNull(cursor)
        cursor!!
        assertArrayEquals(WhatsAppContract.METADATA_COLUMNS, cursor.columnNames)
        assertEquals(1, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(updatedPack.id, cursor.getString(0))
        assertEquals("Device Integration Pack", cursor.getString(1))
        assertEquals("Sticker Maker Test", cursor.getString(2))
        assertEquals(WhatsAppLimits.DEFAULT_TRAY_FILE_NAME, cursor.getString(3))
        assertEquals("", cursor.getString(4))
        assertEquals("", cursor.getString(5))
        assertEquals("", cursor.getString(6))
        assertEquals("", cursor.getString(7))
        assertEquals("", cursor.getString(8))
        assertEquals("", cursor.getString(9))
        assertEquals(updatedPack.imageDataVersion.toString(), cursor.getString(10))
        assertEquals(0, cursor.getInt(11))
        assertEquals(0, cursor.getInt(12))
      }

      resolver.query(stickersUri, null, null, null, null).use { cursor ->
        assertNotNull(cursor)
        cursor!!
        assertArrayEquals(WhatsAppContract.STICKER_COLUMNS, cursor.columnNames)
        assertEquals(3, cursor.count)
        assertTrue(cursor.moveToFirst())
        assertEquals(firstStickerName, cursor.getString(0))
        assertEquals("🔥,🎉", cursor.getString(1))
        assertEquals("", cursor.getString(2))

        var rowCount = 1
        while (cursor.moveToNext()) {
          assertEquals(updatedPack.stickers[rowCount].fileName, cursor.getString(0))
          assertEquals("🙂", cursor.getString(1))
          rowCount++
        }
        assertEquals(3, rowCount)
      }

      resolver.openAssetFileDescriptor(trayUri, "r").use { afd ->
        assertNotNull(afd)
        val trayRead = FileInputStream(afd!!.fileDescriptor).use { it.readBytes() }
        assertArrayEquals(customTrayBytes, trayRead)
      }

      resolver.openAssetFileDescriptor(stickerAssetUri, "r").use { afd ->
        assertNotNull(afd)
        val stickerRead = FileInputStream(afd!!.fileDescriptor).use { it.readBytes() }
        assertArrayEquals(convertedStickers.first().webpBytes, stickerRead)
      }
    } finally {
      store.deletePack(packId)
    }
  }

  @Test
  fun whatsAppAddPackIntentResolvesWhenWhatsAppIsInstalled() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assumeTrue(
      "WhatsApp is not installed on this device",
      WhatsAppPackExporter.isAnyWhatsAppInstalled(context),
    )

    val pack = StickerPack(
      id = "pack_device_intent_check",
      name = "Device Check",
      publisher = "Sticker Maker",
    )
    val intent = WhatsAppPackExporter.createAddPackIntent(pack)
    val packageManager = context.packageManager

    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      packageManager.resolveActivity(intent, 0)
    }
    assertNotNull("Expected WhatsApp activity to resolve ENABLE_STICKER_PACK intent", resolved)

    val resolvedPkg = resolved!!.activityInfo.packageName
    val whatsAppPackages = setOf(
      WhatsAppContract.WHATSAPP_CONSUMER_PACKAGE,
      WhatsAppContract.WHATSAPP_SMB_PACKAGE,
    )
    if (resolvedPkg in whatsAppPackages) {
      assertTrue(resolvedPkg in whatsAppPackages)
    } else {
      val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(intent, 0)
      }
      assertTrue(
        "Expected a WhatsApp handler among resolved activities, got ${candidates.map { it.activityInfo.packageName }}",
        candidates.any { it.activityInfo.packageName in whatsAppPackages },
      )
    }
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

  private fun createPatternBitmap(width: Int, height: Int, seed: Int): Bitmap {
    val random = Random(seed)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val r = ((x * 255) / width + random.nextInt(-25, 26)).coerceIn(0, 255)
        val g = ((y * 255) / height + random.nextInt(-25, 26)).coerceIn(0, 255)
        val b = random.nextInt(0, 256)
        pixels[y * width + x] = Color.argb(255, r, g, b)
      }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  private companion object {
    val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  }
}
