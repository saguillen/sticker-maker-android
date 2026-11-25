package com.stickermaker.whatsapp.whatsapp

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.stickermaker.whatsapp.BuildConfig
import com.stickermaker.whatsapp.domain.StickerPack
import com.stickermaker.whatsapp.domain.WhatsAppContract

sealed interface ExportResult {
  data object Added : ExportResult
  data class ValidationError(val message: String) : ExportResult
  data object Cancelled : ExportResult
  data object WhatsAppNotInstalled : ExportResult
}

object WhatsAppPackExporter {
  private val WHATSAPP_PACKAGES = listOf(
    WhatsAppContract.WHATSAPP_CONSUMER_PACKAGE,
    WhatsAppContract.WHATSAPP_SMB_PACKAGE,
  )

  fun createAddPackIntent(
    pack: StickerPack,
    authority: String = BuildConfig.CONTENT_PROVIDER_AUTHORITY,
    targetPackage: String? = null,
  ): Intent = Intent(WhatsAppContract.ACTION_ENABLE_STICKER_PACK).apply {
    putExtra(WhatsAppContract.EXTRA_STICKER_PACK_ID, pack.id)
    putExtra(WhatsAppContract.EXTRA_STICKER_PACK_AUTHORITY, authority)
    putExtra(WhatsAppContract.EXTRA_STICKER_PACK_NAME, pack.name)
    if (targetPackage != null) {
      setPackage(targetPackage)
    }
  }

  fun parseActivityResult(resultCode: Int, data: Intent?): ExportResult {
    if (resultCode == Activity.RESULT_OK) {
      return ExportResult.Added
    }
    val validationError = data?.getStringExtra(WhatsAppContract.EXTRA_VALIDATION_ERROR)
    if (!validationError.isNullOrBlank()) {
      return ExportResult.ValidationError(validationError)
    }
    return ExportResult.Cancelled
  }

  fun isAnyWhatsAppInstalled(context: Context): Boolean =
    WHATSAPP_PACKAGES.any { isPackageInstalled(context.packageManager, it) }

  fun isPackWhitelisted(
    context: Context,
    packId: String,
    authority: String = BuildConfig.CONTENT_PROVIDER_AUTHORITY,
  ): Boolean = WHATSAPP_PACKAGES.any { pkg ->
    isPackWhitelistedInPackage(context, pkg, packId, authority)
  }

  private fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean =
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
      }
      true
    } catch (_: PackageManager.NameNotFoundException) {
      false
    }

  private fun isPackWhitelistedInPackage(
    context: Context,
    whatsappPackage: String,
    packId: String,
    authority: String,
  ): Boolean {
    if (!isPackageInstalled(context.packageManager, whatsappPackage)) {
      return false
    }
    val whitelistAuthority = "$whatsappPackage${WhatsAppContract.WHITELIST_PROVIDER_SUFFIX}"
    val providerInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.resolveContentProvider(
        whitelistAuthority,
        PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.resolveContentProvider(whitelistAuthority, PackageManager.GET_META_DATA)
    } ?: return false

    if (!providerInfo.enabled) {
      return false
    }

    val queryUri = Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority(whitelistAuthority)
      .appendPath(WhatsAppContract.WHITELIST_QUERY_PATH)
      .appendQueryParameter(WhatsAppContract.WHITELIST_QUERY_PARAM_AUTHORITY, authority)
      .appendQueryParameter(WhatsAppContract.WHITELIST_QUERY_PARAM_IDENTIFIER, packId)
      .build()

    return try {
      context.contentResolver.query(queryUri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return false
        val columnIndex = cursor.getColumnIndex(WhatsAppContract.WHITELIST_RESULT_COLUMN)
        columnIndex >= 0 && cursor.getInt(columnIndex) == 1
      } ?: false
    } catch (_: SecurityException) {
      false
    } catch (_: IllegalArgumentException) {
      false
    }
  }
}
