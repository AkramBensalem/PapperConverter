package me.akram.bensalem.papperconverter.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.runBlocking
import me.akram.bensalem.papperconverter.data.Options
import me.akram.bensalem.papperconverter.service.PdfOcrService
import me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState
import me.akram.bensalem.papperconverter.ui.ConvertPdfDialog
import me.akram.bensalem.papperconverter.util.IoUtil
import me.akram.bensalem.papperconverter.util.Notifications
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class ConvertPdfAction : AnAction() {
    private val log = Logger.getInstance(ConvertPdfAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

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

        // Early validation for API key only if Mistral mode is selected
        if (settings.state.mode == PdfOcrSettingsState.OcrMode.Mistral && (!settings.hasApiKey() || settings.apiKey.isBlank())) {
            Notifications.error(project, "PDF OCR", "API key is not configured. Open Settings/Preferences → Tools → PDF to Markdown OCR and set your API key.")
            return
        }

        val dialog = ConvertPdfDialog(project, pdfs.size, settings)
        if (!dialog.showAndGet()) return

        // Use the selected values from the dialog
        val overwritePolicy = dialog.selectedOverwritePolicy
        val outputMarkdown = dialog.outputMarkdown
        val outputJson = dialog.outputJson

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Converting PDFs to Markdown", true) {
            override fun run(indicator: ProgressIndicator) {
                val service = PdfOcrService.getInstance(e.project ?: return)
                var successCount = 0
                var failCount = 0
                var skippedCount = 0
                val toRefresh = mutableListOf<File>()
                var firstMd: Path? = null
                var firstError: String? = null
                pdfs.forEachIndexed { idx, pdf ->
                    indicator.checkCanceled()
                    indicator.text = "Converting ${pdf.fileName}"
                    indicator.fraction = (idx.toDouble() / pdfs.size)
                    try {
                        val outDir = IoUtil.computeOutputDir(pdf)
                        val options = Options(
                            includeImages = settings.state.includeImages,
                            combinePages = settings.state.combinePages,
                            overwritePolicy = overwritePolicy,
                            mode = settings.state.mode,
                            apiKey = settings.apiKey,
                            outputMarkdown = outputMarkdown,
                            outputJson = outputJson
                        )
                        runBlocking {
                            val result = service.convertPdf(pdf, outDir, options)
                            if (result.error == null && result.createdFiles.isNotEmpty()) {
                                successCount++
                                if (firstMd == null) firstMd = result.markdownFile ?: result.jsonFile
                                result.createdFiles.forEach { toRefresh.add(it.toFile()) }

                                // Move the original PDF file into the output directory
                                try {
                                    val targetPdf = outDir.resolve(pdf.fileName)
                                    java.nio.file.Files.move(pdf, targetPdf, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                    toRefresh.add(targetPdf.toFile())
                                    toRefresh.add(pdf.parent.toFile()) // Refresh source directory
                                } catch (ex: Exception) {
                                    log.warn("Failed to move PDF $pdf to $outDir", ex)
                                }
                            } else if (result.error == null) {
                                skippedCount++

                                if (!pdf.parent.equals(outDir)) {
                                    try {
                                        val targetPdf = outDir.resolve(pdf.fileName)
                                        java.nio.file.Files.move(
                                            pdf,
                                            targetPdf,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                        )
                                        toRefresh.add(targetPdf.toFile())
                                        toRefresh.add(pdf.parent.toFile()) // Refresh source directory
                                    } catch (ex: Exception) {
                                        log.warn("Failed to move skipped PDF $pdf to $outDir", ex)
                                    }
                                }
                                
                                
                                
                            } else {
                                failCount++
                                if (firstError == null) firstError = result.error
                            }
                        }

                    } catch (ex: Exception) {
                        log.warn("Failed to OCR $pdf", ex)
                        failCount++
                        if (firstError == null) firstError = ex.message
                    }
                }

                if (toRefresh.isNotEmpty()) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, *toRefresh.toTypedArray())
                }

                if (successCount == 1 && settings.state.openAfterConvert) {
                    val md = firstMd
                    if (md != null) {
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) {
                                val vf = LocalFileSystem.getInstance().findFileByIoFile(md.toFile())
                                if (vf != null) {
                                    FileEditorManager.getInstance(project).openFile(vf, true)
                                }
                            }
                        }
                    }
                }

                var content = "Converted: $successCount, Failed: $failCount, Skipped: $skippedCount"
                if (failCount > 0 && firstError != null) {
                    content += "\nerror: $firstError"
                }
                when {
                    failCount == 0 -> Notifications.info(project, "PDF OCR", content)
                    successCount == 0 -> Notifications.error(project, "PDF OCR", content)
                    else -> Notifications.warn(project, "PDF OCR", content)
                }
            }
        })
    }
}

private fun VirtualFile.toNioPathOrNull(): Path? = try {
    Paths.get(this.path)
} catch (_: Exception) { null }

