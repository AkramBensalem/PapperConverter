package me.akram.bensalem.papperconverter.data

import java.nio.file.Path

data class OcrResult(
    val markdownFile: Path?,
    val jsonFile: Path?,
    val imageFiles: List<Path>,
    val createdFiles: List<Path>,
    val error: String? = null
)
