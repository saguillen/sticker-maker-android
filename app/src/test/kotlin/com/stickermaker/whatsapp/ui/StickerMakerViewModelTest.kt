package com.stickermaker.whatsapp.ui

import android.net.Uri
import com.stickermaker.whatsapp.data.ConversionError
import com.stickermaker.whatsapp.data.ConversionOutcome
import com.stickermaker.whatsapp.data.ConvertedSticker
import com.stickermaker.whatsapp.data.FileStickerPackStore
import com.stickermaker.whatsapp.data.TrayConversionOutcome
import com.stickermaker.whatsapp.domain.PackId
import com.stickermaker.whatsapp.domain.PackReadiness
import com.stickermaker.whatsapp.domain.PackValidationIssue
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import com.stickermaker.whatsapp.whatsapp.ExportResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickerMakerViewModelTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun createPackTrimsInputsOpensEditorNotifiesProviderAndStartsNotReady() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var notifyCount = 0
    val viewModel = createViewModel(
      store = store,
      onPacksChanged = { notifyCount++ },
    )
    advanceUntilIdle()

    viewModel.openCreateDialog()
    assertTrue(viewModel.uiState.value.isCreateDialogOpen)

    viewModel.createPack("   Cats Pack   ", "  Jane Doe  ")
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertFalse(state.isCreateDialogOpen)
    assertEquals(1, notifyCount)
    assertEquals(1, state.packs.size)

    val editor = state.activeEditor
    assertNotNull(editor)
    editor!!
    assertEquals(Screen.PackEditor(editor.pack.id), state.screen)
    assertEquals("Cats Pack", editor.pack.name)
    assertEquals("Jane Doe", editor.pack.publisher)
    assertNull(editor.trayFile)

    val notReady = editor.readiness as PackReadiness.NotReady
    assertTrue(notReady.issues.contains(PackValidationIssue.MissingTrayIcon))
    assertTrue(
      notReady.issues.contains(
        PackValidationIssue.TooFewStickers(
          current = 0,
          required = WhatsAppLimits.MIN_STICKERS_PER_PACK,
        ),
      ),
    )
  }

  @Test
  fun importingThreeValidImagesTransitionsPackToReadyPersistsFilesAndNotifiesProvider() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var notifyCount = 0
    val viewModel = createViewModel(
      store = store,
      convertImage = { validOutcome() },
      onPacksChanged = { notifyCount++ },
    )
    advanceUntilIdle()

    viewModel.createPack("Memes", "Studio")
    advanceUntilIdle()
    val notificationsAfterCreate = notifyCount

    val uris = listOf(
      Uri.parse("content://media/1"),
      Uri.parse("content://media/2"),
      Uri.parse("content://media/3"),
    )
    viewModel.importImages(uris)
    advanceUntilIdle()

    val editor = viewModel.uiState.value.activeEditor
    assertNotNull(editor)
    editor!!
    assertFalse(editor.isImporting)
    assertEquals(PackReadiness.Ready, editor.readiness)
    assertEquals(3, editor.pack.stickers.size)
    assertEquals(3, editor.stickerFiles.size)
    assertTrue(editor.stickerFiles.values.all { it.isFile })
    assertNotNull(editor.trayFile)
    assertTrue(editor.trayFile!!.isFile)
    assertEquals(notificationsAfterCreate + 1, notifyCount)

    val persisted = store.getPack(PackId(editor.pack.id))
    assertNotNull(persisted)
    assertEquals(3, persisted!!.stickers.size)
  }

  @Test
  fun importingMixedBatchPersistsValidStickerAndReportsAddedAndFailedCounts() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var notifyCount = 0
    val validUri = Uri.parse("content://media/valid")
    val tooLargeUri = Uri.parse("content://media/too-large")
    val unreadableUri = Uri.parse("content://media/unreadable")

    val viewModel = createViewModel(
      store = store,
      convertImage = { uri ->
        when (uri) {
          validUri -> validOutcome()
          tooLargeUri -> ConversionOutcome.Failure(
            ConversionError.ExceedsMaxSize(
              smallestBytes = 140_000,
              maxBytes = WhatsAppLimits.MAX_STICKER_BYTES,
            ),
          )
          unreadableUri -> ConversionOutcome.Failure(ConversionError.UnreadableSource)
          else -> error("Unexpected URI: $uri")
        }
      },
      onPacksChanged = { notifyCount++ },
    )
    advanceUntilIdle()

    viewModel.createPack("Mixed", "Tester")
    advanceUntilIdle()
    val notificationsAfterCreate = notifyCount

    viewModel.importImages(listOf(validUri, tooLargeUri, unreadableUri))
    advanceUntilIdle()

    val state = viewModel.uiState.value
    val editor = state.activeEditor!!
    assertEquals(1, editor.pack.stickers.size)
    assertEquals(notificationsAfterCreate + 1, notifyCount)

    val status = state.statusMessage
    assertNotNull(status)
    assertTrue("Expected added count in status: $status", status!!.contains("1"))
    assertTrue("Expected failed count in status: $status", status.contains("2"))
    assertTrue("Expected failure wording in status: $status", status.contains("failed", ignoreCase = true))
  }

  @Test
  fun removingStickerDropsThreeStickerPackBackToNotReadyAndNotifiesProvider() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var notifyCount = 0
    val viewModel = createViewModel(
      store = store,
      convertImage = { validOutcome() },
      onPacksChanged = { notifyCount++ },
    )
    advanceUntilIdle()

    viewModel.createPack("Removable", "Tester")
    advanceUntilIdle()
    viewModel.importImages(
      listOf(
        Uri.parse("content://media/a"),
        Uri.parse("content://media/b"),
        Uri.parse("content://media/c"),
      ),
    )
    advanceUntilIdle()

    val readyEditor = viewModel.uiState.value.activeEditor!!
    assertEquals(PackReadiness.Ready, readyEditor.readiness)
    val notificationsBeforeRemove = notifyCount

    val firstFileName = readyEditor.pack.stickers.first().fileName
    viewModel.removeSticker(readyEditor.pack.id, firstFileName)
    advanceUntilIdle()

    val updatedEditor = viewModel.uiState.value.activeEditor!!
    assertEquals(2, updatedEditor.pack.stickers.size)
    assertEquals(notificationsBeforeRemove + 1, notifyCount)
    val notReady = updatedEditor.readiness as PackReadiness.NotReady
    assertTrue(
      notReady.issues.contains(
        PackValidationIssue.TooFewStickers(
          current = 2,
          required = WhatsAppLimits.MIN_STICKERS_PER_PACK,
        ),
      ),
    )
  }

  @Test
  fun deletingOpenPackRemovesFromStoreNavigatesBackToListAndNotifiesProvider() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var notifyCount = 0
    val viewModel = createViewModel(
      store = store,
      onPacksChanged = { notifyCount++ },
    )
    advanceUntilIdle()

    viewModel.createPack("Delete Me", "Tester")
    advanceUntilIdle()
    val packId = viewModel.uiState.value.activeEditor!!.pack.id
    val notificationsBeforeDelete = notifyCount

    viewModel.deletePack(packId)
    advanceUntilIdle()

    val state = viewModel.uiState.value
    assertEquals(Screen.PackList, state.screen)
    assertNull(state.activeEditor)
    assertTrue(state.packs.isEmpty())
    assertNull(store.getPack(PackId(packId)))
    assertEquals(notificationsBeforeDelete + 1, notifyCount)
  }

  @Test
  fun onWhatsAppExportResultUpdatesStatusMessageForAllOutcomesAndRefreshesWhitelist() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var whitelisted = false
    val viewModel = createViewModel(
      store = store,
      convertImage = { validOutcome() },
      checkWhitelisted = { whitelisted },
    )
    advanceUntilIdle()

    viewModel.createPack("Export Pack", "Tester")
    advanceUntilIdle()
    viewModel.importImages(
      listOf(
        Uri.parse("content://media/1"),
        Uri.parse("content://media/2"),
        Uri.parse("content://media/3"),
      ),
    )
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.activeEditor!!.isWhitelistedInWhatsApp)

    whitelisted = true
    viewModel.onWhatsAppExportResult(ExportResult.Added)
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.activeEditor!!.isWhitelistedInWhatsApp)
    assertTrue(viewModel.uiState.value.packs.single().isWhitelistedInWhatsApp)
    assertTrue(viewModel.uiState.value.statusMessage!!.contains("added", ignoreCase = true))

    viewModel.onWhatsAppExportResult(ExportResult.ValidationError("invalid tray icon"))
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.statusMessage!!.contains("invalid tray icon"))

    viewModel.onWhatsAppExportResult(ExportResult.WhatsAppNotInstalled)
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.statusMessage!!.contains("not installed", ignoreCase = true))

    viewModel.onWhatsAppExportResult(ExportResult.Cancelled)
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.statusMessage!!.contains("cancel", ignoreCase = true))

    viewModel.clearStatusMessage()
    assertNull(viewModel.uiState.value.statusMessage)
  }

  @Test
  fun importImagesCapsBatchToRemainingSlotsOutOfThirty() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var convertedCalls = 0
    val viewModel = createViewModel(
      store = store,
      convertImage = {
        convertedCalls++
        validOutcome()
      },
    )
    advanceUntilIdle()

    viewModel.createPack("Full Pack", "Tester")
    advanceUntilIdle()

    val initialBatch = (1..29).map { Uri.parse("content://media/$it") }
    viewModel.importImages(initialBatch)
    advanceUntilIdle()
    assertEquals(29, viewModel.uiState.value.activeEditor!!.pack.stickers.size)
    convertedCalls = 0

    val overflowBatch = listOf(
      Uri.parse("content://media/30"),
      Uri.parse("content://media/31"),
      Uri.parse("content://media/32"),
    )
    viewModel.importImages(overflowBatch)
    advanceUntilIdle()

    assertEquals(1, convertedCalls)
    assertEquals(WhatsAppLimits.MAX_STICKERS_PER_PACK, viewModel.uiState.value.activeEditor!!.pack.stickers.size)
    val status = viewModel.uiState.value.statusMessage!!
    assertTrue("Expected added count in status: $status", status.contains("1"))
    assertTrue("Expected skipped count in status: $status", status.contains("2"))
  }

  @Test
  fun setCustomTrayIconAndSetStickerAsTrayIconUpdateTrayNotifyProviderAndHandleFailure() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var notifyCount = 0
    val customTrayBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 7, 7, 7, 7)
    val stickerTrayBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 5, 5, 5, 5)
    val badUri = Uri.parse("content://media/bad-tray")
    val goodUri = Uri.parse("content://media/good-tray")
    var promotedFile: File? = null

    val viewModel = createViewModel(
      store = store,
      convertImage = { validOutcome() },
      convertTrayImage = { uri ->
        if (uri == goodUri) {
          TrayConversionOutcome.Success(customTrayBytes)
        } else {
          TrayConversionOutcome.Failure(ConversionError.UnreadableSource)
        }
      },
      convertTrayFile = { file ->
        promotedFile = file
        TrayConversionOutcome.Success(stickerTrayBytes)
      },
      onPacksChanged = { notifyCount++ },
    )
    advanceUntilIdle()

    viewModel.createPack("Tray Pack", "Tester")
    advanceUntilIdle()
    viewModel.importImages(listOf(Uri.parse("content://media/s1")))
    advanceUntilIdle()

    val editorBefore = viewModel.uiState.value.activeEditor!!
    val versionBefore = editorBefore.pack.imageDataVersion
    val notifyBefore = notifyCount

    viewModel.setCustomTrayIcon(badUri)
    advanceUntilIdle()
    assertEquals(notifyBefore, notifyCount)
    assertEquals(versionBefore, viewModel.uiState.value.activeEditor!!.pack.imageDataVersion)
    assertNotNull(viewModel.uiState.value.statusMessage)

    viewModel.setCustomTrayIcon(goodUri)
    advanceUntilIdle()
    val editorAfterCustom = viewModel.uiState.value.activeEditor!!
    assertEquals(notifyBefore + 1, notifyCount)
    assertEquals(versionBefore + 1, editorAfterCustom.pack.imageDataVersion)
    assertEquals("Tray icon updated", viewModel.uiState.value.statusMessage)
    assertArrayEquals(customTrayBytes, editorAfterCustom.trayFile!!.readBytes())

    val firstSticker = editorAfterCustom.pack.stickers.first().fileName
    viewModel.setStickerAsTrayIcon(editorAfterCustom.pack.id, firstSticker)
    advanceUntilIdle()
    val editorAfterPromote = viewModel.uiState.value.activeEditor!!
    assertEquals(store.stickerFile(editorAfterCustom.pack.id, firstSticker), promotedFile)
    assertEquals(notifyBefore + 2, notifyCount)
    assertEquals(versionBefore + 2, editorAfterPromote.pack.imageDataVersion)
    assertEquals("Tray icon updated", viewModel.uiState.value.statusMessage)
    assertArrayEquals(stickerTrayBytes, editorAfterPromote.trayFile!!.readBytes())
  }

  @Test
  fun updateStickerEmojisValidatesInputPersistsValidEmojisAndNotifiesProvider() = runTest(testDispatcher) {
    val store = FileStickerPackStore(tempFolder.root)
    var notifyCount = 0
    val viewModel = createViewModel(
      store = store,
      convertImage = { validOutcome() },
      onPacksChanged = { notifyCount++ },
    )
    advanceUntilIdle()

    viewModel.createPack("Emoji Pack", "Tester")
    advanceUntilIdle()
    viewModel.importImages(listOf(Uri.parse("content://media/s1")))
    advanceUntilIdle()

    val editor = viewModel.uiState.value.activeEditor!!
    val packId = editor.pack.id
    val stickerName = editor.pack.stickers.first().fileName
    val versionBefore = editor.pack.imageDataVersion
    val notifyBefore = notifyCount

    viewModel.updateStickerEmojis(packId, stickerName, "not emojis")
    advanceUntilIdle()
    assertEquals(notifyBefore, notifyCount)
    assertEquals(versionBefore, viewModel.uiState.value.activeEditor!!.pack.imageDataVersion)
    assertEquals(WhatsAppLimits.DEFAULT_STICKER_EMOJIS, viewModel.uiState.value.activeEditor!!.pack.stickers.first().emojis)
    assertNotNull(viewModel.uiState.value.statusMessage)

    viewModel.updateStickerEmojis(packId, stickerName, "🔥🎉❤️")
    advanceUntilIdle()
    val updatedEditor = viewModel.uiState.value.activeEditor!!
    assertEquals(notifyBefore + 1, notifyCount)
    assertEquals(versionBefore + 1, updatedEditor.pack.imageDataVersion)
    assertEquals(listOf("🔥", "🎉", "❤️"), updatedEditor.pack.stickers.first().emojis)
    assertEquals("Sticker emojis updated", viewModel.uiState.value.statusMessage)
  }

  private fun createViewModel(
    store: FileStickerPackStore,
    convertImage: suspend (Uri) -> ConversionOutcome = { validOutcome() },
    convertTrayImage: suspend (Uri) -> TrayConversionOutcome = { validTrayOutcome() },
    convertTrayFile: suspend (File) -> TrayConversionOutcome = { validTrayOutcome() },
    onPacksChanged: () -> Unit = {},
    checkWhitelisted: (String) -> Boolean = { false },
  ): StickerMakerViewModel = StickerMakerViewModel(
    store = store,
    convertImage = convertImage,
    convertTrayImage = convertTrayImage,
    convertTrayFile = convertTrayFile,
    onPacksChanged = onPacksChanged,
    checkWhitelisted = checkWhitelisted,
    ioDispatcher = testDispatcher,
  )

  private fun validTrayOutcome(): TrayConversionOutcome.Success = TrayConversionOutcome.Success(
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
  )

  private fun validOutcome(): ConversionOutcome.Success = ConversionOutcome.Success(
    ConvertedSticker(
      webpBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x01, 0x02, 0x03, 0x04),
      trayPngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    ),
  )
}
