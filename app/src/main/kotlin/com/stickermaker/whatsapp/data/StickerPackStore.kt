package com.stickermaker.whatsapp.data

import android.content.Context
import com.stickermaker.whatsapp.domain.PackId
import com.stickermaker.whatsapp.domain.StickerFileName
import com.stickermaker.whatsapp.domain.StickerItem
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ResolvedAsset(
  val file: File,
  val mimeType: String,
)

class FileStickerPackStore(
  private val rootDir: File,
) {
  private val lock = Any()
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun listPacks(): List<StickerPack> = synchronized(lock) {
    if (!rootDir.isDirectory) return emptyList()
    val dirs = rootDir.listFiles { file -> file.isDirectory } ?: return emptyList()
    dirs
      .sortedBy { it.name }
      .mapNotNull { dir ->
        val packId = PackId.parseOrNull(dir.name) ?: return@mapNotNull null
        readPackLocked(packId)
      }
  }

  fun getPack(id: PackId): StickerPack? = synchronized(lock) {
    readPackLocked(id)
  }

  fun createPack(name: String, publisher: String): StickerPack = synchronized(lock) {
    val cleanName = name.trim()
    val cleanPublisher = publisher.trim()
    require(cleanName.isNotBlank()) { "Pack name must not be blank" }
    require(cleanName.length <= WhatsAppLimits.MAX_TEXT_LENGTH) { "Pack name exceeds max length" }
    require(cleanPublisher.isNotBlank()) { "Publisher must not be blank" }
    require(cleanPublisher.length <= WhatsAppLimits.MAX_TEXT_LENGTH) { "Publisher exceeds max length" }

    var packId = PackId.generate()
    while (packDir(packId).exists()) {
      packId = PackId.generate()
    }

    val dir = packDir(packId)
    check(dir.mkdirs() || dir.isDirectory) { "Failed to create pack directory: ${dir.path}" }

    val pack = StickerPack(
      id = packId.value,
      name = cleanName,
      publisher = cleanPublisher,
      trayImageFile = WhatsAppLimits.DEFAULT_TRAY_FILE_NAME,
      imageDataVersion = 1,
      stickers = emptyList(),
    )
    writeManifestAtomically(dir, pack)
    pack
  }

  fun addStickers(id: PackId, convertedStickers: List<ConvertedSticker>): StickerPack = synchronized(lock) {
    val existing = readPackLocked(id)
      ?: throw NoSuchElementException("Sticker pack not found: ${id.value}")
    if (convertedStickers.isEmpty()) return existing

    val remainingSlots = (WhatsAppLimits.MAX_STICKERS_PER_PACK - existing.stickers.size).coerceAtLeast(0)
    if (remainingSlots == 0) return existing

    val toAdd = convertedStickers.take(remainingSlots)
    val dir = packDir(id)
    val trayTarget = File(dir, existing.trayImageFile)
    if (!trayTarget.exists()) {
      writeBytesAtomically(trayTarget, toAdd.first().trayPngBytes)
    }

    val newItems = toAdd.map { converted ->
      var fileName = StickerFileName.generateWebp()
      while (File(dir, fileName.value).exists()) {
        fileName = StickerFileName.generateWebp()
      }
      writeBytesAtomically(File(dir, fileName.value), converted.webpBytes)
      StickerItem(
        fileName = fileName.value,
        emojis = WhatsAppLimits.DEFAULT_STICKER_EMOJIS,
        sizeBytes = converted.webpBytes.size.toLong(),
      )
    }

    val updated = existing.copy(
      imageDataVersion = existing.imageDataVersion + 1,
      stickers = existing.stickers + newItems,
    )
    writeManifestAtomically(dir, updated)
    updated
  }

  fun removeSticker(id: PackId, fileName: StickerFileName): StickerPack = synchronized(lock) {
    val existing = readPackLocked(id)
      ?: throw NoSuchElementException("Sticker pack not found: ${id.value}")
    if (existing.stickers.none { it.fileName == fileName.value }) {
      return existing
    }

    val updated = existing.copy(
      imageDataVersion = existing.imageDataVersion + 1,
      stickers = existing.stickers.filterNot { it.fileName == fileName.value },
    )
    val dir = packDir(id)
    writeManifestAtomically(dir, updated)
    File(dir, fileName.value).delete()
    updated
  }

  fun updateTrayIcon(id: PackId, trayPngBytes: ByteArray): StickerPack = synchronized(lock) {
    require(trayPngBytes.isNotEmpty()) { "Tray icon must not be empty" }
    require(trayPngBytes.size <= WhatsAppLimits.MAX_TRAY_ICON_BYTES) {
      "Tray icon exceeds ${WhatsAppLimits.MAX_TRAY_ICON_BYTES} bytes"
    }

    val existing = readPackLocked(id)
      ?: throw NoSuchElementException("Sticker pack not found: ${id.value}")
    val dir = packDir(id)
    val trayFileName = StickerFileName(existing.trayImageFile)
    writeBytesAtomically(File(dir, trayFileName.value), trayPngBytes)

    val updated = existing.copy(imageDataVersion = existing.imageDataVersion + 1)
    writeManifestAtomically(dir, updated)
    updated
  }

  fun updateStickerEmojis(
    id: PackId,
    fileName: StickerFileName,
    emojis: List<String>,
  ): StickerPack = synchronized(lock) {
    require(emojis.size in WhatsAppLimits.MIN_EMOJIS..WhatsAppLimits.MAX_EMOJIS) {
      "Sticker must have between ${WhatsAppLimits.MIN_EMOJIS} and ${WhatsAppLimits.MAX_EMOJIS} emojis"
    }
    require(emojis.all { it.isNotBlank() }) { "Sticker emojis must not be blank" }

    val existing = readPackLocked(id)
      ?: throw NoSuchElementException("Sticker pack not found: ${id.value}")
    if (existing.stickers.none { it.fileName == fileName.value }) {
      throw NoSuchElementException("Sticker not found: ${fileName.value}")
    }

    val updated = existing.copy(
      imageDataVersion = existing.imageDataVersion + 1,
      stickers = existing.stickers.map { item ->
        if (item.fileName == fileName.value) item.copy(emojis = emojis) else item
      },
    )
    writeManifestAtomically(packDir(id), updated)
    updated
  }

  fun deletePack(id: PackId) = synchronized(lock) {
    val dir = packDir(id)
    if (dir.exists()) {
      dir.deleteRecursively()
    }
  }

  fun resolveAssetFile(packId: String, fileName: String): ResolvedAsset? = synchronized(lock) {
    val validPackId = PackId.parseOrNull(packId) ?: return null
    val validFileName = StickerFileName.parseOrNull(fileName) ?: return null
    val pack = readPackLocked(validPackId) ?: return null

    val isRegistered = validFileName.value == pack.trayImageFile ||
      pack.stickers.any { it.fileName == validFileName.value }
    if (!isRegistered) return null

    val dir = packDir(validPackId)
    val file = File(dir, validFileName.value)
    if (!file.isFile) return null
    if (file.canonicalFile.parentFile != dir.canonicalFile) return null

    ResolvedAsset(file = file, mimeType = validFileName.mimeType)
  }

  fun trayFile(packId: String): File {
    val validPackId = PackId(packId)
    val trayName = getPack(validPackId)?.trayImageFile ?: WhatsAppLimits.DEFAULT_TRAY_FILE_NAME
    val validTray = StickerFileName(trayName)
    return File(packDir(validPackId), validTray.value)
  }

  fun stickerFile(packId: String, fileName: String): File {
    val validPackId = PackId(packId)
    val validFileName = StickerFileName(fileName)
    return File(packDir(validPackId), validFileName.value)
  }

  private fun packDir(id: PackId): File = File(rootDir, id.value)

  private fun readPackLocked(id: PackId): StickerPack? {
    val manifest = File(packDir(id), MANIFEST_FILE_NAME)
    if (!manifest.isFile) return null
    return try {
      val parsed = json.decodeFromString<StickerPack>(manifest.readText(Charsets.UTF_8))
      if (parsed.id == id.value) parsed else null
    } catch (_: Exception) {
      null
    }
  }

  private fun writeManifestAtomically(packDir: File, pack: StickerPack) {
    val bytes = json.encodeToString(pack).toByteArray(Charsets.UTF_8)
    writeBytesAtomically(File(packDir, MANIFEST_FILE_NAME), bytes)
  }

  private fun writeBytesAtomically(targetFile: File, bytes: ByteArray) {
    val parent = targetFile.parentFile
      ?: throw IllegalStateException("Target file has no parent: ${targetFile.path}")
    if (!parent.exists()) {
      parent.mkdirs()
    }
    val tmpFile = File(parent, "${targetFile.name}.tmp")
    FileOutputStream(tmpFile).use { fos ->
      fos.write(bytes)
      fos.flush()
      fos.fd.sync()
    }
    try {
      Files.move(
        tmpFile.toPath(),
        targetFile.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(
        tmpFile.toPath(),
        targetFile.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
      )
    }
  }

  companion object {
    const val PACKS_DIR_NAME = "sticker_packs"
    const val MANIFEST_FILE_NAME = "pack.json"

    fun forContext(context: Context): FileStickerPackStore =
      FileStickerPackStore(File(context.filesDir, PACKS_DIR_NAME))
  }
}
