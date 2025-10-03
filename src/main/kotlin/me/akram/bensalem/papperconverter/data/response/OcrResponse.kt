package me.akram.bensalem.papperconverter.data.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

@Serializable
data class OcrResponse(
    val pages: List<Page>
) {
    @Serializable
    data class Page(
        val index: Int,
        val markdown: String,
        val images: List<Image> = emptyList(),
    )

    @Serializable
    data class Image(
        val id: String,
        @SerialName("top_left_x") val topLeftX: Int? = null,
        @SerialName("top_left_y") val topLeftY: Int? = null,
        @SerialName("bottom_right_x") val bottomRightX: Int? = null,
        @SerialName("bottom_right_y") val bottomRightY: Int? = null,
        @SerialName("image_base64") val imageBase64: String? = null,
        @SerialName("image_annotation") val imageAnnotation: String? = null
    ){

        fun toByteArray(): ByteArray? {
            if (imageBase64 == null) return null
            return if (imageBase64.startsWith("data:")) {
                val comma = imageBase64.indexOf(',')
                if (comma >= 0) {
                    val meta = imageBase64.substring(5, comma)
                    val payload = imageBase64.substring(comma + 1)
                    if (meta.contains(";base64")) Base64.getDecoder().decode(payload) else payload.toByteArray()
                } else Base64.getDecoder().decode(imageBase64)
            } else Base64.getDecoder().decode(imageBase64)
        }

        fun toBufferedImage(): BufferedImage? {
            val data = imageBase64?.substringAfter("base64,", imageBase64) ?: return null
            val bytes = Base64.getDecoder().decode(data)
            return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        }

        fun saveTo(path: Path, format: String = "png"): Boolean {
            val img = toBufferedImage() ?: return false
            Files.createDirectories(path.parent)
            return javax.imageio.ImageIO.write(img, format, path.toFile())
        }

    }

    fun toMarkdown(
        stem : String,
        outDir : Path,
    ){
        val mdPath = outDir.resolve("$stem.md")
        val markdown = pages.joinToString("\n\n") { it.markdown }
        Files.writeString(mdPath, markdown)
    }

    fun toJson(
        stem : String,
        outDir : Path,
    ){
        val path = outDir.resolve("$stem.json")
        val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
        Files.writeString(path, json.encodeToString(OcrResponse.serializer(), this))
    }
}