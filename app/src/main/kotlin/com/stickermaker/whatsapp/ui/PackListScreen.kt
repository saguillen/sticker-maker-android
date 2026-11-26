package com.stickermaker.whatsapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.stickermaker.whatsapp.domain.PackReadiness
import com.stickermaker.whatsapp.domain.WhatsAppLimits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackListScreen(
  packs: List<PackSummary>,
  isCreateDialogOpen: Boolean,
  snackbarHostState: SnackbarHostState,
  onOpenCreateDialog: () -> Unit,
  onDismissCreateDialog: () -> Unit,
  onCreatePack: (name: String, publisher: String) -> Unit,
  onOpenPack: (packId: String) -> Unit,
  onDeletePack: (packId: String) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(title = { Text("Sticker Maker") })
    },
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    floatingActionButton = {
      FloatingActionButton(onClick = onOpenCreateDialog) {
        Icon(imageVector = Icons.Default.Add, contentDescription = "Create pack")
      }
    },
  ) { innerPadding ->
    if (packs.isEmpty()) {
      EmptyPacksState(
        onOpenCreateDialog = onOpenCreateDialog,
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(24.dp),
      )
    } else {
      LazyColumn(
        contentPadding = PaddingValues(
          start = 16.dp,
          end = 16.dp,
          top = innerPadding.calculateTopPadding() + 8.dp,
          bottom = innerPadding.calculateBottomPadding() + 88.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(items = packs, key = { it.pack.id }) { summary ->
          PackRowCard(
            summary = summary,
            onClick = { onOpenPack(summary.pack.id) },
            onDelete = { onDeletePack(summary.pack.id) },
          )
        }
      }
    }
  }

  if (isCreateDialogOpen) {
    CreatePackDialog(
      onDismiss = onDismissCreateDialog,
      onCreate = onCreatePack,
    )
  }
}

@Composable
private fun EmptyPacksState(
  onOpenCreateDialog: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier,
  ) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
      Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(24.dp),
      ) {
        Text(
          text = "Create your first WhatsApp sticker pack",
          style = MaterialTheme.typography.titleLarge,
        )
        Text(
          text = "1. Name your pack and publisher.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "2. Import ${WhatsAppLimits.MIN_STICKERS_PER_PACK} to ${WhatsAppLimits.MAX_STICKERS_PER_PACK} images (auto-sized to 512x512 WebP under 100 KB).",
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = "3. Tap Add to WhatsApp to export.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Button(
          onClick = onOpenCreateDialog,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Create Pack")
        }
      }
    }
  }
}

@Composable
private fun PackRowCard(
  summary: PackSummary,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  val statusLabel = when {
    summary.isWhitelistedInWhatsApp -> "Added to WhatsApp"
    summary.readiness is PackReadiness.Ready -> "Ready"
    summary.pack.stickers.size < WhatsAppLimits.MIN_STICKERS_PER_PACK -> {
      val remaining = WhatsAppLimits.MIN_STICKERS_PER_PACK - summary.pack.stickers.size
      "Need $remaining more"
    }
    else -> "Not ready"
  }

  ElevatedCard(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
      LocalImageThumbnail(
        file = summary.trayFile,
        contentDescription = "${summary.pack.name} tray icon",
        cacheKey = summary.pack.imageDataVersion,
        modifier = Modifier
          .size(56.dp)
          .clip(RoundedCornerShape(12.dp)),
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = summary.pack.name,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = summary.pack.publisher,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${summary.pack.stickers.size}/${WhatsAppLimits.MAX_STICKERS_PER_PACK} • $statusLabel",
          style = MaterialTheme.typography.labelMedium,
          color = if (summary.isWhitelistedInWhatsApp || summary.readiness is PackReadiness.Ready) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      IconButton(onClick = onDelete) {
        Icon(
          imageVector = Icons.Default.Delete,
          contentDescription = "Delete pack",
        )
      }
    }
  }
}

@Composable
fun CreatePackDialog(
  onDismiss: () -> Unit,
  onCreate: (name: String, publisher: String) -> Unit,
) {
  var name by rememberSaveable { mutableStateOf("") }
  var publisher by rememberSaveable { mutableStateOf("") }
  val canSubmit = name.isNotBlank() && publisher.isNotBlank()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New Sticker Pack") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { if (it.length <= WhatsAppLimits.MAX_TEXT_LENGTH) name = it },
          label = { Text("Pack name") },
          singleLine = true,
          supportingText = { Text("${name.length}/${WhatsAppLimits.MAX_TEXT_LENGTH}") },
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = publisher,
          onValueChange = { if (it.length <= WhatsAppLimits.MAX_TEXT_LENGTH) publisher = it },
          label = { Text("Publisher") },
          singleLine = true,
          supportingText = { Text("${publisher.length}/${WhatsAppLimits.MAX_TEXT_LENGTH}") },
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onCreate(name, publisher) },
        enabled = canSubmit,
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
