package com.stickermaker.whatsapp.data

import com.stickermaker.whatsapp.domain.PackId
import com.stickermaker.whatsapp.domain.StickerFileName
import com.stickermaker.whatsapp.domain.WhatsAppContract
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStickerPackStoreTest {
  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun lifecyclePersistsManifestAssetsIncrementsVersionAndCleansUpOnDelete() {
    val store = FileStickerPackStore(tempFolder.root)
    val created = store.createPack(name = "  Travel  ", publisher = "  Sam  ")
    val packId = PackId(created.id)
    val packDir = File(tempFolder.root, created.id)

    assertEquals("Travel", created.name)
    assertEquals("Sam", created.publisher)
    assertEquals(1, created.imageDataVersion)
    assertTrue(File(packDir, FileStickerPackStore.MANIFEST_FILE_NAME).isFile)

    val trayBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    val stickerBytes1 = "RIFF0000WEBP1111".toByteArray()
    val stickerBytes2 = "RIFF0000WEBP2222".toByteArray()

    val afterAdd = store.addStickers(
      packId,
      listOf(
        ConvertedSticker(webpBytes = stickerBytes1, trayPngBytes = trayBytes),
        ConvertedSticker(webpBytes = stickerBytes2, trayPngBytes = byteArrayOf(9, 9, 9)),
      ),
    )

    assertEquals(2, afterAdd.imageDataVersion)
    assertEquals(2, afterAdd.stickers.size)
    assertEquals(stickerBytes1.size.toLong(), afterAdd.stickers[0].sizeBytes)

    val resolvedTray = store.resolveAssetFile(created.id, WhatsAppLimits.DEFAULT_TRAY_FILE_NAME)
    assertNotNull(resolvedTray)
    assertEquals(WhatsAppContract.MIME_PNG, resolvedTray!!.mimeType)
    assertArrayEquals(trayBytes, resolvedTray.file.readBytes())

    val firstStickerName = afterAdd.stickers[0].fileName
    val resolvedSticker = store.resolveAssetFile(created.id, firstStickerName)
    assertNotNull(resolvedSticker)
    assertEquals(WhatsAppContract.MIME_WEBP, resolvedSticker!!.mimeType)
    assertArrayEquals(stickerBytes1, resolvedSticker.file.readBytes())

    File(packDir, "orphan.webp").writeBytes(stickerBytes1)
    assertNull(store.resolveAssetFile(created.id, "orphan.webp"))
    assertNull(store.resolveAssetFile(created.id, "../pack.json"))
    assertNull(store.resolveAssetFile("../escape", firstStickerName))

    val afterRemove = store.removeSticker(packId, StickerFileName(firstStickerName))
    assertEquals(3, afterRemove.imageDataVersion)
    assertEquals(1, afterRemove.stickers.size)
    assertFalse(File(packDir, firstStickerName).exists())
    assertNull(store.resolveAssetFile(created.id, firstStickerName))

    val afterSecondRemove = store.removeSticker(packId, StickerFileName(firstStickerName))
    assertEquals(3, afterSecondRemove.imageDataVersion)

    store.deletePack(packId)
    assertFalse(packDir.exists())
    assertNull(store.getPack(packId))
    assertTrue(store.listPacks().isEmpty())
  }

  @Test
  fun listPacksSkipsCorruptedDirectoriesAndAddStickersCapsAtThirty() {
    val store = FileStickerPackStore(tempFolder.root)

    val corruptedDir = File(tempFolder.root, "pack_corrupt").apply { mkdirs() }
    File(corruptedDir, FileStickerPackStore.MANIFEST_FILE_NAME).writeText("{not valid json")
    File(tempFolder.root, "pack_empty_dir").mkdirs()

    val valid = store.createPack("Daily", "Studio")
    val batch = List(35) { i ->
      ConvertedSticker(
        webpBytes = "RIFF0000WEBP_$i".toByteArray(),
        trayPngBytes = byteArrayOf(1, 2, 3, 4),
      )
    }

    val updated = store.addStickers(PackId(valid.id), batch)
    assertEquals(WhatsAppLimits.MAX_STICKERS_PER_PACK, updated.stickers.size)

    val listed = store.listPacks()
    assertEquals(1, listed.size)
    assertEquals(valid.id, listed.single().id)
  }
}
