package com.stickermaker.whatsapp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainBehaviorTest {
  @Test
  fun packIdRejectsInvalidInputsAndAcceptsValidIdentifiers() {
    assertNull(PackId.parseOrNull(""))
    assertNull(PackId.parseOrNull("   "))
    assertNull(PackId.parseOrNull(".."))
    assertNull(PackId.parseOrNull("../escape"))
    assertNull(PackId.parseOrNull("pack/slash"))
    assertNull(PackId.parseOrNull("pack\\backslash"))
    assertNull(PackId.parseOrNull("bad!char"))
    assertNull(PackId.parseOrNull("a".repeat(WhatsAppLimits.MAX_TEXT_LENGTH + 1)))

    val valid = PackId("my.pack_01-beta 2")
    assertEquals("my.pack_01-beta 2", valid.value)
    assertTrue(PackId.generate().value.startsWith("pack_"))
  }

  @Test
  fun stickerFileNameRejectsTraversalAndUnsupportedExtensions() {
    assertNull(StickerFileName.parseOrNull(""))
    assertNull(StickerFileName.parseOrNull("../secret.webp"))
    assertNull(StickerFileName.parseOrNull("sub/sticker.webp"))
    assertNull(StickerFileName.parseOrNull("sub\\sticker.webp"))
    assertNull(StickerFileName.parseOrNull("photo.jpg"))
    assertNull(StickerFileName.parseOrNull(".webp"))
    assertNull(StickerFileName.parseOrNull("..webp"))

    val webp = StickerFileName("sticker_01.webp")
    val png = StickerFileName("tray.png")
    assertEquals(WhatsAppContract.MIME_WEBP, webp.mimeType)
    assertEquals(WhatsAppContract.MIME_PNG, png.mimeType)
  }

  @Test
  fun evaluatePackReadinessReportsAllBlockingIssuesAndReadyState() {
    val validStickers = List(3) { i ->
      StickerItem(fileName = "s$i.webp", sizeBytes = 40_000L)
    }
    val validPack = StickerPack(
      id = "pack_valid",
      name = "Cats",
      publisher = "Alice",
      stickers = validStickers,
    )
    assertEquals(PackReadiness.Ready, evaluatePackReadiness(validPack, trayExists = true))

    val tooFew = evaluatePackReadiness(validPack.copy(stickers = validStickers.take(2)), trayExists = true)
    assertEquals(
      PackReadiness.NotReady(listOf(PackValidationIssue.TooFewStickers(current = 2, required = 3))),
      tooFew,
    )

    val tooManyStickers = List(31) { i -> StickerItem(fileName = "s$i.webp", sizeBytes = 10_000L) }
    val tooMany = evaluatePackReadiness(validPack.copy(stickers = tooManyStickers), trayExists = true)
    assertEquals(
      PackReadiness.NotReady(listOf(PackValidationIssue.TooManyStickers(current = 31, max = 30))),
      tooMany,
    )

    val oversizedSticker = StickerItem(fileName = "big.webp", sizeBytes = WhatsAppLimits.MAX_STICKER_BYTES + 1L)
    val brokenPack = validPack.copy(
      name = "   ",
      publisher = "",
      stickers = listOf(validStickers[0], validStickers[1], oversizedSticker),
    )
    val brokenResult = evaluatePackReadiness(brokenPack, trayExists = false) as PackReadiness.NotReady
    assertEquals(
      listOf(
        PackValidationIssue.BlankName,
        PackValidationIssue.BlankPublisher,
        PackValidationIssue.MissingTrayIcon,
        PackValidationIssue.StickerExceedsMaxSize("big.webp", WhatsAppLimits.MAX_STICKER_BYTES + 1L),
      ),
      brokenResult.issues,
    )

    val longText = "x".repeat(WhatsAppLimits.MAX_TEXT_LENGTH + 1)
    val longResult = evaluatePackReadiness(
      validPack.copy(name = longText, publisher = longText),
      trayExists = true,
    ) as PackReadiness.NotReady
    assertEquals(
      listOf(PackValidationIssue.NameTooLong, PackValidationIssue.PublisherTooLong),
      longResult.issues,
    )

    val invalidEmojiStickers = listOf(
      StickerItem(fileName = "empty.webp", emojis = emptyList(), sizeBytes = 10_000L),
      StickerItem(fileName = "too_many.webp", emojis = listOf("🙂", "😂", "🔥", "🎉"), sizeBytes = 10_000L),
      validStickers[2],
    )
    val invalidEmojiResult = evaluatePackReadiness(
      validPack.copy(stickers = invalidEmojiStickers),
      trayExists = true,
    ) as PackReadiness.NotReady
    assertEquals(
      listOf(
        PackValidationIssue.InvalidStickerEmojis("empty.webp", 0),
        PackValidationIssue.InvalidStickerEmojis("too_many.webp", 4),
      ),
      invalidEmojiResult.issues,
    )
  }

  @Test
  fun parseStickerEmojisHandlesSingleMultiConcatenatedGraphemesAndRejectsInvalidInputs() {
    assertEquals(EmojiParseResult.Valid(listOf("🙂")), parseStickerEmojis("🙂"))
    assertEquals(EmojiParseResult.Valid(listOf("🔥", "🎉")), parseStickerEmojis("🔥 🎉"))
    assertEquals(EmojiParseResult.Valid(listOf("🔥", "🎉", "😂")), parseStickerEmojis("🔥, 🎉 ,😂"))
    assertEquals(EmojiParseResult.Valid(listOf("🔥", "🎉")), parseStickerEmojis("🔥🎉"))
    assertEquals(EmojiParseResult.Valid(listOf("❤️")), parseStickerEmojis("❤️"))
    assertEquals(EmojiParseResult.Valid(listOf("👍🏽")), parseStickerEmojis("👍🏽"))
    assertEquals(EmojiParseResult.Valid(listOf("❤️", "👍🏽", "🔥")), parseStickerEmojis("❤️👍🏽🔥"))

    assertEquals(
      EmojiParseResult.Invalid(EmojiValidationError.Empty),
      parseStickerEmojis(""),
    )
    assertEquals(
      EmojiParseResult.Invalid(EmojiValidationError.Empty),
      parseStickerEmojis("   ,  "),
    )
    assertEquals(
      EmojiParseResult.Invalid(EmojiValidationError.TooMany(count = 4, max = WhatsAppLimits.MAX_EMOJIS)),
      parseStickerEmojis("🙂😂🔥🎉"),
    )
    assertEquals(
      EmojiParseResult.Invalid(EmojiValidationError.NonEmojiInput),
      parseStickerEmojis("abc"),
    )
    assertEquals(
      EmojiParseResult.Invalid(EmojiValidationError.NonEmojiInput),
      parseStickerEmojis("123"),
    )
    assertEquals(
      EmojiParseResult.Invalid(EmojiValidationError.NonEmojiInput),
      parseStickerEmojis("🔥a"),
    )
  }

  @Test
  fun computeFitInsideRectPreservesAspectRatioAndCentersInside512Square() {
    assertEquals(
      FitRect(left = 0, top = 128, width = 512, height = 256),
      computeFitInsideRect(srcWidth = 1000, srcHeight = 500, targetSize = 512),
    )
    assertEquals(
      FitRect(left = 128, top = 0, width = 256, height = 512),
      computeFitInsideRect(srcWidth = 400, srcHeight = 800, targetSize = 512),
    )
    assertEquals(
      FitRect(left = 0, top = 0, width = 512, height = 512),
      computeFitInsideRect(srcWidth = 512, srcHeight = 512, targetSize = 512),
    )
  }

  @Test
  fun encodeWithinBudgetReturnsMaxQualityWhenUnderBudget() {
    val attempted = mutableListOf<Int>()
    val result = encodeWithinBudget(maxBytes = 1000, minQuality = 5, maxQuality = 90) { quality ->
      attempted += quality
      ByteArray(quality * 10)
    }

    assertEquals(listOf(90), attempted)
    val success = result as EncodeResult.Success
    assertEquals(90, success.quality)
    assertEquals(900, success.bytes.size)
  }

  @Test
  fun encodeWithinBudgetFindsHighestFittingQualityViaBinarySearch() {
    val attempted = mutableListOf<Int>()
    val result = encodeWithinBudget(maxBytes = 630, minQuality = 5, maxQuality = 90) { quality ->
      attempted += quality
      ByteArray(quality * 10)
    }

    val success = result as EncodeResult.Success
    assertEquals(63, success.quality)
    assertEquals(630, success.bytes.size)
    assertTrue("Expected binary search (<= 10 calls), got ${attempted.size}", attempted.size <= 10)
  }

  @Test
  fun encodeWithinBudgetReturnsExceedsBudgetWhenEvenMinQualityIsTooLarge() {
    val result = encodeWithinBudget(maxBytes = 40, minQuality = 5, maxQuality = 90) { quality ->
      ByteArray(quality * 10)
    }

    assertEquals(EncodeResult.ExceedsBudget(smallestBytes = 50, maxBytes = 40), result)
  }
}
