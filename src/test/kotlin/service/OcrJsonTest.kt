package service

import kotlinx.serialization.json.Json
import me.akram.bensalem.papperconverter.service.PdfOcrService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrJsonTest {
    @Test
    fun `parse sample ocr response`() {
        val sample = """
            {"pages":[
              {"markdown":"Hello","images":[{"id":"img1.png","image_base64":"aGVsbG8="}]},
              {"markdown":"World","images":[]}
            ]}
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString(PdfOcrService.OcrResponse.serializer(), sample)
        assertEquals(2, parsed.pages.size)
        assertEquals("Hello", parsed.pages[0].markdown)
        assertEquals("img1.png", parsed.pages[0].images[0].id)
    }
}
