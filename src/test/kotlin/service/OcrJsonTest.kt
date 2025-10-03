package service

import kotlinx.serialization.json.Json
import me.akram.bensalem.papperconverter.data.response.OcrResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrJsonTest {
    @Test
    fun `parse sample ocr response`() {
        val sample = """
            {"pages":[
              {"index":0,"markdown":"Hello","images":[{"id":"img1.png","image_base64":"aGVsbG8="}]},
              {"index":1,"markdown":"World","images":[]}
            ]}
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.decodeFromString(OcrResponse.serializer(), sample)
        assertEquals(2, parsed.pages.size)
        assertEquals("Hello", parsed.pages[0].markdown)
        assertEquals("img1.png", parsed.pages[0].images[0].id)
    }
}
