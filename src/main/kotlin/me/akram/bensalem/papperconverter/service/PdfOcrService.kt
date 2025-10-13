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
            // Basic JSON
            install(ContentNegotiation) { json(json) }
            // Conservative timeouts to avoid hanging
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
     * Converts PDF to Markdown using GraalPy + MarkItDown (offline mode)
     */
    private fun runOfflineConversion(pdf: Path, targetDir: Path, options: Options): OcrResult {
        try {
            log.info("Starting offline PDF conversion with MarkItDown for: $pdf")

            val pdfPath = pdf.toAbsolutePath().toString().replace("\\", "\\\\")
            val stem = pdf.fileName.toString().substringBeforeLast('.')
            val created = mutableListOf<Path>()

            // Create GraalPy context
            val context = Context.newBuilder("python")
                .allowIO(true)
                .allowAllAccess(true)
                .option("python.ForceImportSite", "false")
                .build()

            // Python code to run MarkItDown
            val pythonCode = """
failed = False
try:
    from markitdown import MarkItDown
except ImportError:
    try:
        import sys, subprocess
        subprocess.check_call([sys.executable, "-m", "pip", "install", "markitdown[all]"])
        from markitdown import MarkItDown
    except Exception as e:
        print("ERROR:MarkItDown library not found and installation failed. Try: pip install markitdown[all]. Reason: " + str(e))
        failed = True

if not failed:
    try:
        md = MarkItDown()
        result = md.convert("$pdfPath")
        print("SUCCESS:" + result.text_content)
    except Exception as e:
        print("ERROR:" + str(e))
""".trimIndent()

            log.info("Executing MarkItDown conversion via GraalPy")
            val output = context.eval("python", pythonCode).toString()
            context.close()

            // Parse the output
            if (output.startsWith("SUCCESS:")) {
                val markdownContent = output.removePrefix("SUCCESS:")

                // Write Markdown if enabled
                var mdFile: Path? = null
                if (options.outputMarkdown) {
                    val mdTarget = targetDir.resolve("$stem.md")
                    mdFile = IoUtil.writeText(mdTarget, markdownContent, options.overwritePolicy)
                    if (mdFile != null) created.add(mdFile)
                }

                return OcrResult(
                    markdownFile = mdFile,
                    jsonFile = null,
                    imageFiles = emptyList(),
                    createdFiles = created
                )
            } else if (output.startsWith("ERROR:")) {
                val errorMsg = output.removePrefix("ERROR:")
                log.warn("MarkItDown conversion failed: $errorMsg")
                return OcrResult(
                    markdownFile = null,
                    jsonFile = null,
                    imageFiles = emptyList(),
                    createdFiles = emptyList(),
                    error = "Offline conversion failed: $errorMsg"
                )
            } else {
                log.warn("Unexpected output from MarkItDown: $output")
                return OcrResult(
                    markdownFile = null,
                    jsonFile = null,
                    imageFiles = emptyList(),
                    createdFiles = emptyList(),
                    error = "Unexpected output from MarkItDown converter\n $output"
                )
            }
        } catch (e: PolyglotException) {
            log.warn("GraalPy execution failed", e)
            return OcrResult(
                markdownFile = null,
                jsonFile = null,
                imageFiles = emptyList(),
                createdFiles = emptyList(),
                error = "Python execution failed: ${e.message}\n\nPlease ensure MarkItDown is installed: pip install markitdown"
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

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PdfOcrService = project.service()
        val base = "https://api.mistral.ai"
    }
}
