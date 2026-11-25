package com.stickermaker.whatsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.stickermaker.whatsapp.ui.StickerMakerApp
import com.stickermaker.whatsapp.ui.StickerMakerViewModel
import com.stickermaker.whatsapp.ui.theme.StickerMakerTheme

class MainActivity : ComponentActivity() {
  private val viewModel: StickerMakerViewModel by viewModels {
    StickerMakerViewModel.factory(applicationContext)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      StickerMakerTheme {
        StickerMakerApp(viewModel = viewModel)
      }
    }
  }
}
