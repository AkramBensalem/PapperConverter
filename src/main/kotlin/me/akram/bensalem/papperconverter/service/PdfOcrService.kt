package me.akram.bensalem.papperconverter.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import me.akram.bensalem.papperconverter.data.OcrResult
import me.akram.bensalem.papperconverter.data.Options
import me.akram.bensalem.papperconverter.data.TestResult
import me.akram.bensalem.papperconverter.data.request.OcrRequest
import me.akram.bensalem.papperconverter.data.response.OcrResponse
import me.akram.bensalem.papperconverter.data.response.SignedUrlResponse
import me.akram.bensalem.papperconverter.data.response.UploadResponse
import me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState.OcrMode
import me.akram.bensalem.papperconverter.util.IoUtil
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

@Service(Service.Level.PROJECT)
class PdfOcrService {
    private val log = Logger.getInstance(PdfOcrService::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                }
            }
        }
    }

    suspend fun uploadFile(pdf: Path, apiKey: String): UploadResponse {
        val response = client.post("${base}/v1/files") {
            headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
            val file = pdf.toFile()
            setBody(MultiPartFormDataContent(formData {
                append("purpose", "ocr")
                append("file", file.readBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                    append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                })
            }))
        }

        if (response.status.value !in 200..299) {
            val errorBody = response.body<String>()
            log.warn("Upload failed with status ${response.status.value}: $errorBody")
            throw Exception("Upload failed: ${response.status.value} - $errorBody")
        }

        return response.body()
    }

    suspend fun signUrl(id: String, apiKey: String): SignedUrlResponse = client.get("${base}/v1/files/$id/url") {
        headers { append(HttpHeaders.Authorization, "Bearer ${apiKey}") }
    }.body()

    suspend fun ocr(request: OcrRequest, apiKey: String): OcrResponse {
        return client.post("${base}/v1/ocr") {
            headers { append(HttpHeaders.Authorization, "Bearer ${apiKey}") }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Converts PDF to Markdown using external MarkItDown CLI (offline mode)
     */
    private fun runOfflineConversion(pdf: Path, targetDir: Path, options: Options): OcrResult {
        try {
            log.info("Starting offline PDF conversion via MarkItDown CLI for: $pdf")

            val settings = me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState.getInstance().state
            val cmd = settings.markitdownCmd.ifBlank { "markitdown" }
            val pdfPath = pdf.toAbsolutePath().toString()
            val stem = pdf.fileName.toString().substringBeforeLast('.')
            val created = mutableListOf<Path>()

            val process = ProcessBuilder(cmd, pdfPath)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()

            if (exit == 0) {
                // Write Markdown if enabled
                var mdFile: Path? = null
                if (options.outputMarkdown) {
                    val mdTarget = targetDir.resolve("$stem.md")
                    mdFile = IoUtil.writeText(mdTarget, stdout, options.overwritePolicy)
                    if (mdFile != null) created.add(mdFile)
                }

                return OcrResult(
                    markdownFile = mdFile,
                    jsonFile = null,
                    imageFiles = emptyList(),
                    createdFiles = created
                )
            } else {
                val msg = (stderr.ifBlank { stdout }).trim()
                log.warn("MarkItDown CLI failed (exit $exit): $msg")
                return OcrResult(
                    markdownFile = null,
                    jsonFile = null,
                    imageFiles = emptyList(),
                    createdFiles = emptyList(),
                    error = "Offline conversion failed: MarkItDown CLI exited with code $exit.\n$msg"
                )
            }
        } catch (e: java.io.IOException) {
            log.warn("MarkItDown CLI not found or failed to start", e)
            return OcrResult(
                markdownFile = null,
                jsonFile = null,
                imageFiles = emptyList(),
                createdFiles = emptyList(),
                error = "MarkItDown CLI not found. Please install it and ensure it's available on PATH, or set its full path in Settings (Tools → PDF to Markdown OCR). Details: ${e.message}"
            )
        } catch (e: Exception) {
            log.warn("Offline conversion failed", e)
            return OcrResult(
                markdownFile = null,
                jsonFile = null,
                imageFiles = emptyList(),
                createdFiles = emptyList(),
                error = "Offline conversion error: ${e.message}"
            )
        }
    }

    suspend fun convertPdf(
        pdf: Path,
        targetDir: Path,
        options: Options
    ): OcrResult {
        return try {
            IoUtil.ensureDir(targetDir)

            // If Offline mode is selected, use local GraalPy + MarkItDown processing
            if (options.mode == OcrMode.Offline) {
                return runOfflineConversion(pdf, targetDir, options)
            }

            val ocrResponse: OcrResponse = runMistralOCR(pdf, options)
            val pageMarkdowns = ocrResponse.pages.map { it.markdown }

            val imagesToWrite: List<Pair<String, ByteArray>> =
                ocrResponse.pages
                    .flatMap { page -> page.images }
                    .mapNotNull { img -> img.toByteArray()?.let { img.id to it } }

            val stem = pdf.fileName.toString().substringBeforeLast('.')

            val created = mutableListOf<Path>()

            // Write Markdown if enabled
            var mdFile: Path? = null
            if (options.outputMarkdown) {
                val mdTarget = targetDir.resolve("$stem.md")
                mdFile = IoUtil.writeText(
                    mdTarget,
                    joinMarkdown(pageMarkdowns, options.combinePages),
                    options.overwritePolicy
                )
                if (mdFile != null) created.add(mdFile)
            }

            // Write JSON if enabled
            var jsonFile: Path? = null
            if (options.outputJson) {
                val jsonTarget = targetDir.resolve("$stem.json")
                val jsonContent = json.encodeToString(OcrResponse.serializer(), ocrResponse)
                jsonFile = IoUtil.writeText(jsonTarget, jsonContent, options.overwritePolicy)
                if (jsonFile != null) created.add(jsonFile)
            }

            val imageFiles = mutableListOf<Path>()
            if (options.includeImages && imagesToWrite.isNotEmpty()) {
                for ((id, bytes) in imagesToWrite) {
                    val imgTarget = targetDir.resolve(id)
                    IoUtil.writeBytes(imgTarget, bytes, options.overwritePolicy)?.let {
                        imageFiles.add(it)
                        created.add(it)
                    }
                }
            }

            OcrResult(markdownFile = mdFile, jsonFile = jsonFile, imageFiles = imageFiles, createdFiles = created)
        } catch (e: Exception) {
            log.warn("PDF OCR failed for $pdf", e)
            OcrResult(
                markdownFile = null,
                jsonFile = null,
                imageFiles = emptyList(),
                createdFiles = emptyList(),
                error = friendlyMessage(e)
            )
        }
    }

    private fun joinMarkdown(pages: List<String>, combine: Boolean): String =
        if (combine) pages.joinToString("\n\n") else pages.joinToString("\n\n")

    private suspend fun friendlyMessage(t: Throwable): String {
        return when (t) {
            is UnknownHostException -> "No internet or DNS issue. Please check your connection and proxy settings."
            is ConnectException -> "Could not connect to OCR service. Check your internet connection, proxy, or firewall."
            is java.net.SocketTimeoutException -> "The request to the OCR service timed out. Please try again."
            is SSLException -> "Secure connection (SSL) failed. Check your network or proxy certificates."
            is ClientRequestException -> "Request error ${t.response.status.value}. Please verify your API key and input."
            is ServerResponseException -> "Server error ${t.response.status.value}. Please try again later."
            is ResponseException -> "HTTP error ${t.response.status.value}."
            else -> t.message ?: "Unknown error"
        }
    }

    private suspend fun runMistralOCR(pdf: Path, options: Options): OcrResponse {
        if (options.apiKey.isBlank()) throw Exception("API key is missing. Set it in Settings/Preferences → Tools → PDF to Markdown OCR.")
        return try {
            val upload: UploadResponse = uploadFile(pdf, options.apiKey)
            val document: SignedUrlResponse = signUrl(upload.id, options.apiKey)
            val request = OcrRequest(
                model = "mistral-ocr-latest",
                document = OcrRequest.Document(type = "document_url", documentUrl = document.url),
                includeImageBase64 = true
            )
            ocr(request, options.apiKey)
        } catch (e: Exception) {
            log.warn("Mistral OCR call failed", e)
            throw e
        }
    }

    suspend fun testConnection(apiKey: String): TestResult {
        if (apiKey.isBlank()) return TestResult(
            false,
            "API key is missing. Set it in Settings/Preferences → Tools → PDF to Markdown OCR."
        )
        return try {
            val resp = client.get("$base/v1/models") {
                headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
            }
            val ok = resp.status.value in 200..299
            if (ok) TestResult(true, "Connection OK ${resp.status.value}") else TestResult(
                false,
                "HTTP error ${resp.status.value}"
            )
        } catch (e: Exception) {
            log.warn("Test connection failed", e)
            TestResult(false, friendlyMessage(e))
        }
    }

    fun checkMarkItDown(cmd: String): TestResult {
        return try {
            val process = ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exit = process.waitFor()
            if (exit == 0 && output.isNotBlank()) {
                TestResult(true, output)
            } else {
                TestResult(false, if (output.isNotBlank()) output else "Unknown error running MarkItDown")
            }
        } catch (e: java.io.IOException) {
            TestResult(false, "MarkItDown CLI not found. Please install it and ensure it's on PATH, or set its full path. Details: ${e.message}")
        } catch (e: Exception) {
            TestResult(false, "Failed to run MarkItDown: ${e.message}")
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PdfOcrService = project.service()
        val base = "https://api.mistral.ai"
    }
}
