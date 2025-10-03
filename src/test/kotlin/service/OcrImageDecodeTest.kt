package service

import me.akram.bensalem.papperconverter.data.response.OcrResponse
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OcrImageDecodeTest {
    @Test
    fun `toByteArray returns null for malformed base64`() {
        val img = OcrResponse.Image(
            id = "bad.png",
            imageBase64 = "not-base64-@@@"
        )
        val bytes = img.toByteArray()
        assertNull(bytes)
    }

    @Test
    fun `toByteArray returns null for data URI with invalid base64`() {
        val img = OcrResponse.Image(
            id = "bad2.png",
            imageBase64 = "data:image/png;base64,____"
        )
        val bytes = img.toByteArray()
        assertNull(bytes)
    }
}
