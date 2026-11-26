package com.stickermaker.whatsapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stickermaker.whatsapp.domain.EmojiParseResult
import com.stickermaker.whatsapp.domain.EmojiValidationError
import com.stickermaker.whatsapp.domain.PackReadiness
import com.stickermaker.whatsapp.domain.PackValidationIssue
import com.stickermaker.whatsapp.domain.StickerItem
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppLimits
import com.stickermaker.whatsapp.domain.parseStickerEmojis
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackEditorScreen(
  editor: PackEditorState,
  snackbarHostState: SnackbarHostState,
  onNavigateBack: () -> Unit,
  onImportClick: (remainingSlots: Int) -> Unit,
  onAddToWhatsAppClick: (StickerPack) -> Unit,
  onRemoveSticker: (fileName: String) -> Unit,
  onChangeTrayIconClick: () -> Unit,
  onUpdateStickerEmojis: (fileName: String, rawEmojis: String) -> Unit,
  onSetStickerAsTrayIcon: (fileName: String) -> Unit,
) {
  val remainingSlots = (WhatsAppLimits.MAX_STICKERS_PER_PACK - editor.pack.stickers.size)
    .coerceAtLeast(0)
  var editingStickerFileName by rememberSaveable { mutableStateOf<String?>(null) }
  val editingSticker = editingStickerFileName?.let { fileName ->
    editor.pack.stickers.firstOrNull { it.fileName == fileName }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
            )
          }
        },
        title = {
          Column {
            Text(
              text = editor.pack.name,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = editor.pack.publisher,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    bottomBar = {
      Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          FilledTonalButton(
            onClick = { onImportClick(remainingSlots) },
            enabled = remainingSlots > 0 && !editor.isImporting,
            modifier = Modifier.weight(1f),
          ) {
            Text("Import Images")
          }
          Button(
            onClick = { onAddToWhatsAppClick(editor.pack) },
            enabled = editor.readiness is PackReadiness.Ready && !editor.isImporting,
            modifier = Modifier.weight(1f),
          ) {
            Text("Add to WhatsApp")
          }
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      ReadinessStatusCard(
        editor = editor,
        onChangeTrayIconClick = onChangeTrayIconClick,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
      )

      if (editor.pack.stickers.isEmpty()) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        ) {
          Text(
            text = "Import ${WhatsAppLimits.MIN_STICKERS_PER_PACK} to ${WhatsAppLimits.MAX_STICKERS_PER_PACK} images to populate this sticker pack.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 104.dp),
          contentPadding = PaddingValues(16.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(items = editor.pack.stickers, key = { it.fileName }) { item ->
            StickerGridTile(
              item = item,
              file = editor.stickerFiles[item.fileName],
              canEdit = !editor.isImporting,
              onEdit = { editingStickerFileName = item.fileName },
              onRemove = { onRemoveSticker(item.fileName) },
            )
          }
        }
      }
    }
  }

  if (editingSticker != null) {
    EditStickerDialog(
      item = editingSticker,
      onDismiss = { editingStickerFileName = null },
      onSaveEmojis = { rawEmojis ->
        onUpdateStickerEmojis(editingSticker.fileName, rawEmojis)
        editingStickerFileName = null
      },
      onSetAsTrayIcon = {
        onSetStickerAsTrayIcon(editingSticker.fileName)
        editingStickerFileName = null
      },
    )
  }
}

@Composable
private fun ReadinessStatusCard(
  editor: PackEditorState,
  onChangeTrayIconClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val headline = when {
    editor.isWhitelistedInWhatsApp -> "Added to WhatsApp"
    editor.readiness is PackReadiness.Ready -> "Ready for WhatsApp"
    else -> "Not ready yet"
  }

  Card(modifier = modifier) {
    Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        LocalImageThumbnail(
          file = editor.trayFile,
          contentDescription = "Tray icon",
          cacheKey = editor.pack.imageDataVersion,
          modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !editor.isImporting, onClick = onChangeTrayIconClick),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = "${editor.pack.stickers.size}/${WhatsAppLimits.MAX_STICKERS_PER_PACK} stickers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(
          onClick = onChangeTrayIconClick,
          enabled = !editor.isImporting,
        ) {
          Text("Change tray")
        }
      }

      if (editor.readiness is PackReadiness.NotReady) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          for (issue in editor.readiness.issues) {
            Text(
              text = "• ${formatValidationIssue(issue)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (editor.isImporting) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
          text = "Converting images to 512x512 WebP...",
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@Composable
private fun StickerGridTile(
  item: StickerItem,
  file: File?,
  canEdit: Boolean,
  onEdit: () -> Unit,
  onRemove: () -> Unit,
) {
  val sizeKb = (item.sizeBytes + 1023) / 1024
  ElevatedCard {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f),
      ) {
        LocalImageThumbnail(
          file = file,
          contentDescription = item.fileName,
          modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        )
        IconButton(
          onClick = onRemove,
          enabled = canEdit,
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(32.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Remove sticker",
            modifier = Modifier.size(18.dp),
          )
        }
      }
      AssistChip(
        onClick = onEdit,
        enabled = canEdit,
        label = {
          Text(
            text = item.emojis.joinToString(" "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        modifier = Modifier.padding(horizontal = 8.dp),
      )
      Text(
        text = "$sizeKb KB",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
      )
    }
  }
}

@Composable
private fun EditStickerDialog(
  item: StickerItem,
  onDismiss: () -> Unit,
  onSaveEmojis: (rawEmojis: String) -> Unit,
  onSetAsTrayIcon: () -> Unit,
) {
  var rawInput by rememberSaveable(item.fileName) {
    mutableStateOf(item.emojis.joinToString(" "))
  }
  val parseResult = parseStickerEmojis(rawInput)
  val selectedEmojis = (parseResult as? EmojiParseResult.Valid)?.emojis.orEmpty()

  val supportingMessage = when (parseResult) {
    is EmojiParseResult.Valid -> "${parseResult.emojis.size}/${WhatsAppLimits.MAX_EMOJIS} emojis"
    is EmojiParseResult.Invalid -> when (val error = parseResult.error) {
      EmojiValidationError.Empty -> "Enter at least ${WhatsAppLimits.MIN_EMOJIS} emoji"
      is EmojiValidationError.TooMany -> "Use at most ${error.max} emojis (${error.count} entered)"
      EmojiValidationError.NonEmojiInput -> "Enter emojis only"
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Edit Sticker") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = rawInput,
          onValueChange = { rawInput = it },
          label = { Text("Emojis (1–3)") },
          singleLine = true,
          isError = parseResult is EmojiParseResult.Invalid,
          supportingText = { Text(supportingMessage) },
          modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          for (row in QUICK_PICK_EMOJIS.chunked(4)) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              for (emoji in row) {
                val isSelected = emoji in selectedEmojis
                FilterChip(
                  selected = isSelected,
                  onClick = {
                    val updated = when (parseResult) {
                      is EmojiParseResult.Valid -> {
                        if (isSelected) {
                          parseResult.emojis.filterNot { it == emoji }
                        } else if (parseResult.emojis.size < WhatsAppLimits.MAX_EMOJIS) {
                          parseResult.emojis + emoji
                        } else {
                          parseResult.emojis
                        }
                      }
                      is EmojiParseResult.Invalid -> {
                        if (parseResult.error == EmojiValidationError.Empty) {
                          listOf(emoji)
                        } else {
                          null
                        }
                      }
                    }
                    if (updated != null) {
                      rawInput = updated.joinToString(" ")
                    }
                  },
                  label = { Text(emoji) },
                  modifier = Modifier.weight(1f),
                )
              }
            }
          }
        }
        OutlinedButton(
          onClick = onSetAsTrayIcon,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Use as tray icon")
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSaveEmojis(rawInput) },
        enabled = parseResult is EmojiParseResult.Valid,
      ) {
        Text("Save")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

private val QUICK_PICK_EMOJIS = listOf("🙂", "😂", "🔥", "❤️", "👍", "🎉", "😍", "😎")

private fun formatValidationIssue(issue: PackValidationIssue): String = when (issue) {
  PackValidationIssue.BlankName -> "Pack name is required"
  PackValidationIssue.BlankPublisher -> "Publisher is required"
  PackValidationIssue.NameTooLong -> "Pack name exceeds ${WhatsAppLimits.MAX_TEXT_LENGTH} characters"
  PackValidationIssue.PublisherTooLong -> "Publisher exceeds ${WhatsAppLimits.MAX_TEXT_LENGTH} characters"
  PackValidationIssue.MissingTrayIcon -> "Import an image to generate the 96x96 tray icon"
  is PackValidationIssue.TooFewStickers -> {
    val missing = (issue.required - issue.current).coerceAtLeast(1)
    "Add at least $missing more ${if (missing == 1) "sticker" else "stickers"} (${issue.current}/${issue.required})"
  }
  is PackValidationIssue.TooManyStickers -> "Remove ${issue.current - issue.max} stickers (max ${issue.max})"
  is PackValidationIssue.StickerExceedsMaxSize -> {
    "${issue.fileName} exceeds 100 KB (${(issue.sizeBytes + 1023) / 1024} KB)"
  }
  is PackValidationIssue.InvalidStickerEmojis -> {
    "${issue.fileName} must have ${WhatsAppLimits.MIN_EMOJIS}–${WhatsAppLimits.MAX_EMOJIS} emojis (found ${issue.count})"
  }
}
