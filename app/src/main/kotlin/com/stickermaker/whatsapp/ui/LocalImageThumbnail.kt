package com.stickermaker.whatsapp.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LocalImageThumbnail(
  file: File?,
  contentDescription: String?,
  modifier: Modifier = Modifier,
) {
  val bitmap by produceState<ImageBitmap?>(
    initialValue = null,
    key1 = file?.absolutePath,
    key2 = file?.lastModified(),
  ) {
    value = if (file != null && file.isFile) {
      withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
      }
    } else {
      null
    }
  }

  val current = bitmap
  if (current != null) {
    Image(
      bitmap = current,
      contentDescription = contentDescription,
      contentScale = ContentScale.Fit,
      modifier = modifier,
    )
  } else {
    Box(
      modifier = modifier
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    )
  }
}
