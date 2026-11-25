package com.stickermaker.whatsapp.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stickermaker.whatsapp.domain.PackReadiness
import com.stickermaker.whatsapp.domain.PackValidationIssue
import com.stickermaker.whatsapp.domain.StickerItem
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppLimits
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
) {
  val remainingSlots = (WhatsAppLimits.MAX_STICKERS_PER_PACK - editor.pack.stickers.size)
    .coerceAtLeast(0)

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
              canRemove = !editor.isImporting,
              onRemove = { onRemoveSticker(item.fileName) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ReadinessStatusCard(
  editor: PackEditorState,
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
          modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
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
  canRemove: Boolean,
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
          enabled = canRemove,
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
      Text(
        text = "$sizeKb KB",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
      )
    }
  }
}

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
}
