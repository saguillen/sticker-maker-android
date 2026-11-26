package com.stickermaker.whatsapp.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import com.stickermaker.whatsapp.whatsapp.ExportResult
import com.stickermaker.whatsapp.whatsapp.WhatsAppPackExporter

@Composable
fun StickerMakerApp(viewModel: StickerMakerViewModel) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val context = LocalContext.current

  val multiplePhotoPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(
      maxItems = WhatsAppLimits.MAX_STICKERS_PER_PACK,
    ),
  ) { uris ->
    if (uris.isNotEmpty()) {
      viewModel.importImages(uris)
    }
  }

  val singlePhotoPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    if (uri != null) {
      viewModel.importImages(listOf(uri))
    }
  }

  val trayIconPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    if (uri != null) {
      viewModel.setCustomTrayIcon(uri)
    }
  }

  val whatsAppExportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    viewModel.onWhatsAppExportResult(
      WhatsAppPackExporter.parseActivityResult(result.resultCode, result.data),
    )
  }

  LaunchedEffect(uiState.statusMessage) {
    val message = uiState.statusMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(message)
    viewModel.clearStatusMessage()
  }

  BackHandler(enabled = uiState.screen is Screen.PackEditor) {
    viewModel.navigateBack()
  }

  val editor = uiState.activeEditor
  if (uiState.screen is Screen.PackEditor && editor != null) {
    PackEditorScreen(
      editor = editor,
      snackbarHostState = snackbarHostState,
      onNavigateBack = viewModel::navigateBack,
      onImportClick = { remainingSlots ->
        when {
          remainingSlots >= 2 -> multiplePhotoPicker.launch(
            PickVisualMediaRequest(
              mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly,
              maxItems = remainingSlots,
            ),
          )
          remainingSlots == 1 -> singlePhotoPicker.launch(
            PickVisualMediaRequest(
              mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly,
            ),
          )
        }
      },
      onAddToWhatsAppClick = { pack ->
        if (!WhatsAppPackExporter.isAnyWhatsAppInstalled(context)) {
          viewModel.onWhatsAppExportResult(ExportResult.WhatsAppNotInstalled)
        } else {
          val intent = WhatsAppPackExporter.createAddPackIntent(pack)
          try {
            whatsAppExportLauncher.launch(intent)
          } catch (_: ActivityNotFoundException) {
            viewModel.onWhatsAppExportResult(ExportResult.WhatsAppNotInstalled)
          }
        }
      },
      onRemoveSticker = { fileName ->
        viewModel.removeSticker(editor.pack.id, fileName)
      },
      onChangeTrayIconClick = {
        trayIconPicker.launch(
          PickVisualMediaRequest(
            mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly,
          ),
        )
      },
      onUpdateStickerEmojis = { fileName, rawEmojis ->
        viewModel.updateStickerEmojis(editor.pack.id, fileName, rawEmojis)
      },
      onSetStickerAsTrayIcon = { fileName ->
        viewModel.setStickerAsTrayIcon(editor.pack.id, fileName)
      },
    )
  } else {
    PackListScreen(
      packs = uiState.packs,
      isCreateDialogOpen = uiState.isCreateDialogOpen,
      snackbarHostState = snackbarHostState,
      onOpenCreateDialog = viewModel::openCreateDialog,
      onDismissCreateDialog = viewModel::dismissCreateDialog,
      onCreatePack = viewModel::createPack,
      onOpenPack = viewModel::openPack,
      onDeletePack = viewModel::deletePack,
    )
  }
}
