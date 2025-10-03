package me.akram.bensalem.papperconverter.util

import me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension

object IoUtil {

    fun listPdfsRecursively(paths: List<Path>): List<Path> = buildList {
        for (p in paths) {
            if (p.isDirectory()) {
                Files.walk(p).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.extension.equals("pdf", ignoreCase = true) }
                        .forEach { add(it) }
                }
            } else if (Files.isRegularFile(p) && p.extension.equals("pdf", true)) {
                add(p)
            }
        }
    }

    fun computeOutputDir(
        projectBase: Path?,
        pdf: Path,
        settings: PdfOcrSettingsState
    ): Path {
        val stem = pdf.nameWithoutExtension
        return when (settings.state.outputMode) {
            PdfOcrSettingsState.OutputMode.AlongsidePdf -> pdf.parent.resolve(stem)
            PdfOcrSettingsState.OutputMode.ProjectOutputRoot -> {
                val root = settings.state.projectOutputRoot?.let { Path.of(it) }
                    ?: (projectBase ?: pdf.parent)
                val rel = try {
                    (projectBase ?: pdf.parent).relativize(pdf.parent)
                } catch (_: Exception) {
                    Path.of("")
                }
                root.resolve(rel).resolve(stem)
            }
        }
    }

    fun ensureDir(dir: Path) {
        Files.createDirectories(dir)
    }

    fun base64ToBytes(data: String): ByteArray = Base64.getDecoder().decode(data)

    fun writeBytes(target: Path, bytes: ByteArray, overwrite: PdfOcrSettingsState.OverwritePolicy): Path? {
        val final = when (overwrite) {
            PdfOcrSettingsState.OverwritePolicy.Overwrite -> target
            PdfOcrSettingsState.OverwritePolicy.SkipExisting -> if (target.exists()) return null else target
            PdfOcrSettingsState.OverwritePolicy.WithSuffix -> nextAvailable(target)
        }
        ensureDir(final.parent)
        Files.write(final, bytes)
        return final
    }

    fun writeText(target: Path, text: String, overwrite: PdfOcrSettingsState.OverwritePolicy): Path? =
        writeBytes(target, text.toByteArray(Charsets.UTF_8), overwrite)

    fun nextAvailable(target: Path): Path {
        if (!target.exists()) return target
        val parent = target.parent
        val fileName = target.fileName.toString()
        val dot = fileName.lastIndexOf('.')
        val base = if (dot >= 0) fileName.substring(0, dot) else fileName
        val ext = if (dot >= 0) fileName.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = parent.resolve("$base ($i)$ext")
            if (!Files.exists(candidate)) return candidate
            i++
        }
    }
}
