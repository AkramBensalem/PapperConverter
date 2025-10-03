package me.akram.bensalem.papperconverter.data

import me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState

data class Options(
    val includeImages: Boolean,
    val combinePages: Boolean,
    val overwritePolicy: PdfOcrSettingsState.OverwritePolicy,
    val apiKey: String,
    val outputMarkdown: Boolean,
    val outputJson: Boolean
)