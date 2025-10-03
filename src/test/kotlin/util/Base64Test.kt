package util

import me.akram.bensalem.papperconverter.util.IoUtil
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class Base64Test {
    @Test
    fun `decode base64`() {
        val original = byteArrayOf(1,2,3,4,5,6,7)
        val b64 = java.util.Base64.getEncoder().encodeToString(original)
        val decoded = IoUtil.base64ToBytes(b64)
        assertArrayEquals(original, decoded)
    }
}
