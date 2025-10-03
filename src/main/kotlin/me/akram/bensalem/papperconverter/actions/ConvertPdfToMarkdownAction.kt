package me.akram.bensalem.papperconverter.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import me.akram.bensalem.papperconverter.service.PdfOcrService
import me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState
import me.akram.bensalem.papperconverter.util.IoUtil
import me.akram.bensalem.papperconverter.util.Notifications
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class ConvertPdfToMarkdownAction : AnAction() {
    private val log = Logger.getInstance(ConvertPdfToMarkdownAction::class.java)

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val show = files.any { it.isDirectory || it.extension.equals("pdf", true) }
        e.presentation.isEnabledAndVisible = show
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        val selectedPaths = vFiles.mapNotNull { vf ->
            val io = vf.toNioPathOrNull()
            io
        }
        val pdfs = IoUtil.listPdfsRecursively(selectedPaths)
        if (pdfs.isEmpty()) {
            Notifications.warn(project, "PDF OCR", "No PDF files found in the selection.")
            return
        }

        val settings = PdfOcrSettingsState.getInstance()
        val message = buildString {
            append("Convert ")
            append(pdfs.size)
            append(" PDF(s)?\nOutput: ")
            append(settings.state.outputMode)
            append("\nOverwrite: ")
            append(settings.state.overwritePolicy)
        }
        val ok = Messages.showYesNoDialog(project, message, "Convert PDF to Markdown", "Convert", "Cancel", null)
        if (ok != Messages.YES) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Converting PDFs to Markdown", true) {
            override fun run(indicator: ProgressIndicator) {
                val service = PdfOcrService.getInstance(project)
                var successCount = 0
                var failCount = 0
                val toRefresh = mutableListOf<File>()
                var firstMd: Path? = null
                pdfs.forEachIndexed { idx, pdf ->
                    indicator.checkCanceled()
                    indicator.text = "Converting ${pdf.fileName}"
                    indicator.fraction = (idx.toDouble() / pdfs.size)
                    try {
                        val outDir = IoUtil.computeOutputDir(project.basePath?.let { Paths.get(it) }, pdf, settings)
                        val options = PdfOcrService.Options(
                            includeImages = settings.state.includeImages,
                            combinePages = settings.state.combinePages,
                            overwritePolicy = settings.state.overwritePolicy,
                            apiKey = settings.apiKey
                        )
                        runBlocking {
                            val result = service.convertPdf(pdf, outDir, options)
                            if (result.error == null && result.markdownFile != null) {
                                successCount++
                                if (firstMd == null) firstMd = result.markdownFile
                                result.createdFiles.forEach { toRefresh.add(it.toFile()) }
                            } else {
                                failCount++
                            }
                        }

                    } catch (ex: Exception) {
                        log.warn("Failed to OCR $pdf", ex)
                        failCount++
                    }
                }

                if (toRefresh.isNotEmpty()) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, *toRefresh.toTypedArray())
                }

                if (successCount == 1 && settings.state.openAfterConvert) {
                    val md = firstMd
                    if (md != null) {
                        val vf = LocalFileSystem.getInstance().findFileByIoFile(md.toFile())
                        if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                }

                val content = "Converted: $successCount, Failed: $failCount"
                if (failCount == 0) Notifications.info(project, "PDF OCR", content) else Notifications.warn(project, "PDF OCR", content)
            }
        })
    }
}

private fun VirtualFile.toNioPathOrNull(): Path? = try {
    Paths.get(this.path)
} catch (_: Exception) { null }
