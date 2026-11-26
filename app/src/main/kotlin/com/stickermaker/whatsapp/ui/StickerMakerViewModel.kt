package com.stickermaker.whatsapp.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stickermaker.whatsapp.data.AndroidStickerImageConverter
import com.stickermaker.whatsapp.data.ConversionError
import com.stickermaker.whatsapp.data.ConversionOutcome
import com.stickermaker.whatsapp.data.ConvertedSticker
import com.stickermaker.whatsapp.data.FileStickerPackStore
import com.stickermaker.whatsapp.data.TrayConversionOutcome
import com.stickermaker.whatsapp.domain.EmojiParseResult
import com.stickermaker.whatsapp.domain.EmojiValidationError
import com.stickermaker.whatsapp.domain.PackId
import com.stickermaker.whatsapp.domain.PackReadiness
import com.stickermaker.whatsapp.domain.StickerFileName
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import com.stickermaker.whatsapp.domain.evaluatePackReadiness
import com.stickermaker.whatsapp.domain.parseStickerEmojis
import com.stickermaker.whatsapp.whatsapp.ExportResult
import com.stickermaker.whatsapp.whatsapp.StickerContentProvider
import com.stickermaker.whatsapp.whatsapp.WhatsAppPackExporter
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
  data object PackList : Screen
  data class PackEditor(val packId: String) : Screen
}

data class PackSummary(
  val pack: StickerPack,
  val trayFile: File?,
  val readiness: PackReadiness,
  val isWhitelistedInWhatsApp: Boolean,
)

data class PackEditorState(
  val pack: StickerPack,
  val trayFile: File?,
  val stickerFiles: Map<String, File>,
  val readiness: PackReadiness,
  val isWhitelistedInWhatsApp: Boolean,
  val isImporting: Boolean,
)

data class UiState(
  val screen: Screen = Screen.PackList,
  val packs: List<PackSummary> = emptyList(),
  val activeEditor: PackEditorState? = null,
  val isCreateDialogOpen: Boolean = false,
  val statusMessage: String? = null,
)

class StickerMakerViewModel(
  private val store: FileStickerPackStore,
  private val convertImage: suspend (Uri) -> ConversionOutcome,
  private val convertTrayImage: suspend (Uri) -> TrayConversionOutcome,
  private val convertTrayFile: suspend (File) -> TrayConversionOutcome,
  private val onPacksChanged: () -> Unit,
  private val checkWhitelisted: (String) -> Boolean,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

  private val _uiState = MutableStateFlow(UiState())
  val uiState: StateFlow<UiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val snapshot = loadSnapshot(targetScreen = Screen.PackList)
      applySnapshot(snapshot)
    }
  }

  fun openCreateDialog() {
    _uiState.update { it.copy(isCreateDialogOpen = true) }
  }

  fun dismissCreateDialog() {
    _uiState.update { it.copy(isCreateDialogOpen = false) }
  }

  fun createPack(name: String, publisher: String) {
    val cleanName = name.trim()
    val cleanPublisher = publisher.trim()
    if (cleanName.isBlank() || cleanPublisher.isBlank()) {
      _uiState.update { it.copy(statusMessage = "Pack name and publisher are required") }
      return
    }
    if (cleanName.length > WhatsAppLimits.MAX_TEXT_LENGTH ||
      cleanPublisher.length > WhatsAppLimits.MAX_TEXT_LENGTH
    ) {
      _uiState.update {
        it.copy(
          statusMessage = "Pack name and publisher must be at most ${WhatsAppLimits.MAX_TEXT_LENGTH} characters",
        )
      }
      return
    }

    viewModelScope.launch {
      val created = withContext(ioDispatcher) {
        val pack = store.createPack(cleanName, cleanPublisher)
        onPacksChanged()
        pack
      }
      val snapshot = loadSnapshot(targetScreen = Screen.PackEditor(created.id))
      _uiState.update { state ->
        state.copy(
          screen = snapshot.screen,
          packs = snapshot.packs,
          activeEditor = snapshot.activeEditor,
          isCreateDialogOpen = false,
        )
      }
    }
  }

  fun openPack(packId: String) {
    viewModelScope.launch {
      val snapshot = loadSnapshot(targetScreen = Screen.PackEditor(packId))
      applySnapshot(snapshot)
    }
  }

  fun navigateBack() {
    _uiState.update { it.copy(screen = Screen.PackList, activeEditor = null) }
  }

  fun deletePack(packId: String) {
    val validPackId = PackId.parseOrNull(packId) ?: return
    viewModelScope.launch {
      withContext(ioDispatcher) {
        store.deletePack(validPackId)
        onPacksChanged()
      }
      val currentScreen = _uiState.value.screen
      val nextScreen = if (currentScreen == Screen.PackEditor(packId)) {
        Screen.PackList
      } else {
        currentScreen
      }
      val snapshot = loadSnapshot(targetScreen = nextScreen)
      applySnapshot(snapshot)
    }
  }

  fun importImages(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val editor = _uiState.value.activeEditor ?: return
    if (editor.isImporting) return
    val validPackId = PackId.parseOrNull(editor.pack.id) ?: return

    val remainingSlots = (WhatsAppLimits.MAX_STICKERS_PER_PACK - editor.pack.stickers.size)
      .coerceAtLeast(0)
    if (remainingSlots == 0) {
      _uiState.update {
        it.copy(statusMessage = "Pack already has ${WhatsAppLimits.MAX_STICKERS_PER_PACK} stickers")
      }
      return
    }

    _uiState.update { state ->
      val active = state.activeEditor?.takeIf { it.pack.id == editor.pack.id }
      state.copy(activeEditor = active?.copy(isImporting = true))
    }

    viewModelScope.launch {
      val toProcess = uris.take(remainingSlots)
      val skippedOverLimit = (uris.size - toProcess.size).coerceAtLeast(0)

      val summary = try {
        withContext(ioDispatcher) {
          val converted = mutableListOf<ConvertedSticker>()
          var tooLarge = 0
          var unreadable = 0

          for (uri in toProcess) {
            when (val outcome = convertImage(uri)) {
              is ConversionOutcome.Success -> converted += outcome.converted
              is ConversionOutcome.Failure -> when (outcome.error) {
                is ConversionError.ExceedsMaxSize -> tooLarge++
                ConversionError.UnreadableSource -> unreadable++
              }
            }
          }

          if (converted.isNotEmpty()) {
            store.addStickers(validPackId, converted)
            onPacksChanged()
          }

          formatImportSummary(
            added = converted.size,
            tooLarge = tooLarge,
            unreadable = unreadable,
            skippedOverLimit = skippedOverLimit,
          )
        }
      } catch (_: NoSuchElementException) {
        "Sticker pack no longer exists"
      }

      val snapshot = loadSnapshot(targetScreen = _uiState.value.screen, isImporting = false)
      _uiState.update { state ->
        state.copy(
          screen = snapshot.screen,
          packs = snapshot.packs,
          activeEditor = snapshot.activeEditor,
          statusMessage = summary,
        )
      }
    }
  }

  fun removeSticker(packId: String, fileName: String) {
    val validPackId = PackId.parseOrNull(packId) ?: return
    val validFileName = StickerFileName.parseOrNull(fileName) ?: return
    viewModelScope.launch {
      withContext(ioDispatcher) {
        store.removeSticker(validPackId, validFileName)
        onPacksChanged()
      }
      val snapshot = loadSnapshot(targetScreen = _uiState.value.screen)
      applySnapshot(snapshot)
    }
  }

  fun setCustomTrayIcon(uri: Uri) {
    val editor = _uiState.value.activeEditor ?: return
    if (editor.isImporting) return
    val validPackId = PackId.parseOrNull(editor.pack.id) ?: return

    viewModelScope.launch {
      val message = try {
        withContext(ioDispatcher) {
          applyTrayConversion(validPackId, convertTrayImage(uri))
        }
      } catch (_: NoSuchElementException) {
        "Sticker pack no longer exists"
      }
      val snapshot = loadSnapshot(targetScreen = _uiState.value.screen)
      applySnapshot(snapshot, statusMessage = message)
    }
  }

  fun setStickerAsTrayIcon(packId: String, fileName: String) {
    val validPackId = PackId.parseOrNull(packId) ?: return
    val validFileName = StickerFileName.parseOrNull(fileName) ?: return

    viewModelScope.launch {
      val message = try {
        withContext(ioDispatcher) {
          val file = store.stickerFile(validPackId.value, validFileName.value)
          applyTrayConversion(validPackId, convertTrayFile(file))
        }
      } catch (_: NoSuchElementException) {
        "Sticker pack no longer exists"
      }
      val snapshot = loadSnapshot(targetScreen = _uiState.value.screen)
      applySnapshot(snapshot, statusMessage = message)
    }
  }

  fun updateStickerEmojis(packId: String, fileName: String, rawEmojis: String) {
    val validPackId = PackId.parseOrNull(packId) ?: return
    val validFileName = StickerFileName.parseOrNull(fileName) ?: return

    val parsed = parseStickerEmojis(rawEmojis)
    if (parsed is EmojiParseResult.Invalid) {
      val message = when (val error = parsed.error) {
        EmojiValidationError.Empty -> "Enter at least ${WhatsAppLimits.MIN_EMOJIS} emoji"
        is EmojiValidationError.TooMany -> "Use at most ${error.max} emojis"
        EmojiValidationError.NonEmojiInput -> "Enter emojis only (1–${WhatsAppLimits.MAX_EMOJIS})"
      }
      _uiState.update { it.copy(statusMessage = message) }
      return
    }

    val validEmojis = (parsed as EmojiParseResult.Valid).emojis
    viewModelScope.launch {
      val message = try {
        withContext(ioDispatcher) {
          store.updateStickerEmojis(validPackId, validFileName, validEmojis)
          onPacksChanged()
          "Sticker emojis updated"
        }
      } catch (_: NoSuchElementException) {
        "Sticker no longer exists"
      }
      val snapshot = loadSnapshot(targetScreen = _uiState.value.screen)
      applySnapshot(snapshot, statusMessage = message)
    }
  }

  fun onWhatsAppExportResult(result: ExportResult) {
    val message = when (result) {
      ExportResult.Added -> "Sticker pack added to WhatsApp"
      is ExportResult.ValidationError -> "WhatsApp rejected pack: ${result.message}"
      ExportResult.WhatsAppNotInstalled -> "WhatsApp is not installed on this device"
      ExportResult.Cancelled -> "Cancelled adding pack to WhatsApp"
    }
    viewModelScope.launch {
      val snapshot = loadSnapshot(targetScreen = _uiState.value.screen)
      _uiState.update { state ->
        state.copy(
          screen = snapshot.screen,
          packs = snapshot.packs,
          activeEditor = snapshot.activeEditor,
          statusMessage = message,
        )
      }
    }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  private fun applyTrayConversion(packId: PackId, outcome: TrayConversionOutcome): String =
    when (outcome) {
      is TrayConversionOutcome.Success -> {
        store.updateTrayIcon(packId, outcome.trayPngBytes)
        onPacksChanged()
        "Tray icon updated"
      }
      is TrayConversionOutcome.Failure -> when (outcome.error) {
        is ConversionError.ExceedsMaxSize -> "Tray icon exceeds 50 KB"
        ConversionError.UnreadableSource -> "Could not read selected image"
      }
    }

  private fun applySnapshot(snapshot: Snapshot, statusMessage: String? = null) {
    _uiState.update { state ->
      state.copy(
        screen = snapshot.screen,
        packs = snapshot.packs,
        activeEditor = snapshot.activeEditor,
        statusMessage = statusMessage ?: state.statusMessage,
      )
    }
  }

  private suspend fun loadSnapshot(
    targetScreen: Screen,
    isImporting: Boolean = false,
  ): Snapshot = withContext(ioDispatcher) {
    val packs = store.listPacks()
    val summaries = packs.map { pack ->
      val tray = store.trayFile(pack.id).takeIf { it.isFile }
      PackSummary(
        pack = pack,
        trayFile = tray,
        readiness = evaluatePackReadiness(pack, trayExists = tray != null),
        isWhitelistedInWhatsApp = checkWhitelisted(pack.id),
      )
    }

    val targetPackId = (targetScreen as? Screen.PackEditor)?.packId
    val matchedSummary = targetPackId?.let { id -> summaries.firstOrNull { it.pack.id == id } }
    val resolvedScreen = if (matchedSummary != null) targetScreen else Screen.PackList
    val editorState = matchedSummary?.let { summary ->
      val stickerFiles = summary.pack.stickers.associate { sticker ->
        sticker.fileName to store.stickerFile(summary.pack.id, sticker.fileName)
      }
      PackEditorState(
        pack = summary.pack,
        trayFile = summary.trayFile,
        stickerFiles = stickerFiles,
        readiness = summary.readiness,
        isWhitelistedInWhatsApp = summary.isWhitelistedInWhatsApp,
        isImporting = isImporting,
      )
    }

    Snapshot(
      screen = resolvedScreen,
      packs = summaries,
      activeEditor = editorState,
    )
  }

  private data class Snapshot(
    val screen: Screen,
    val packs: List<PackSummary>,
    val activeEditor: PackEditorState?,
  )

  private fun formatImportSummary(
    added: Int,
    tooLarge: Int,
    unreadable: Int,
    skippedOverLimit: Int,
  ): String {
    val failed = tooLarge + unreadable
    val parts = mutableListOf<String>()
    if (added > 0) {
      parts += "Added $added ${if (added == 1) "sticker" else "stickers"}"
    }
    if (failed > 0) {
      parts += when {
        tooLarge > 0 && unreadable == 0 -> "$failed failed (over 100 KB)"
        unreadable > 0 && tooLarge == 0 -> "$failed failed (unreadable)"
        else -> "$failed failed"
      }
    }
    if (skippedOverLimit > 0) {
      parts += "$skippedOverLimit skipped (limit ${WhatsAppLimits.MAX_STICKERS_PER_PACK})"
    }
    return if (parts.isEmpty()) "No stickers imported" else parts.joinToString(", ")
  }

  companion object {
    fun factory(context: Context): ViewModelProvider.Factory {
      val appContext = context.applicationContext
      val store = FileStickerPackStore.forContext(appContext)
      val converter = AndroidStickerImageConverter(appContext.contentResolver)
      return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          require(modelClass.isAssignableFrom(StickerMakerViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
          }
          val instance = StickerMakerViewModel(
            store = store,
            convertImage = { uri -> converter.convert(uri) },
            convertTrayImage = { uri -> converter.convertTrayImage(uri) },
            convertTrayFile = { file -> converter.convertTrayFile(file) },
            onPacksChanged = { StickerContentProvider.notifyPacksChanged(appContext) },
            checkWhitelisted = { id -> WhatsAppPackExporter.isPackWhitelisted(appContext, id) },
          )
          return requireNotNull(modelClass.cast(instance))
        }
      }
    }
  }
}
