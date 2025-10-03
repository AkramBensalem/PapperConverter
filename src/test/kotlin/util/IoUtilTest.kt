package util

import me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState
import me.akram.bensalem.papperconverter.util.IoUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class IoUtilTest {

    @Test
    fun `nextAvailable adds suffix`() {
        val dir = Files.createTempDirectory("iou")
        val base = dir.resolve("file.md")
        Files.writeString(base, "a")
        val candidate = IoUtil.nextAvailable(base)
        assertTrue(candidate.fileName.toString().startsWith("file ("))
        assertTrue(candidate.fileName.toString().endsWith(").md"))
    }

    @Test
    fun `writeText SkipExisting returns null when exists`() {
        val dir = Files.createTempDirectory("iou")
        val base = dir.resolve("f.md")
        Files.writeString(base, "a")
        val res = IoUtil.writeText(base, "b", PdfOcrSettingsState.OverwritePolicy.SkipExisting)
        assertEquals(null, res)
        assertEquals("a", Files.readString(base))
    }
}
