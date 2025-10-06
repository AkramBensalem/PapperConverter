package me.akram.bensalem.papperconverter.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.client.request.forms.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.akram.bensalem.papperconverter.data.OcrResult
import me.akram.bensalem.papperconverter.data.Options
import me.akram.bensalem.papperconverter.data.TestResult
import me.akram.bensalem.papperconverter.data.request.OcrRequest
import me.akram.bensalem.papperconverter.data.response.OcrResponse
import me.akram.bensalem.papperconverter.data.response.SignedUrlResponse
import me.akram.bensalem.papperconverter.data.response.UploadResponse
import me.akram.bensalem.papperconverter.util.IoUtil
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class PdfOcrService {
    private val log = Logger.getInstance(PdfOcrService::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
        }
    }

    suspend fun uploadFile(pdf: Path, apiKey: String) : UploadResponse {
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

    suspend fun signUrl(id: String, apiKey: String) : SignedUrlResponse = client.get("${base}/v1/files/$id/url"){
        headers { append(HttpHeaders.Authorization, "Bearer ${apiKey}") }
    }.body()

    suspend fun ocr(request : OcrRequest, apiKey: String): OcrResponse {
        return client.post("${base}/v1/ocr") {
            headers { append(HttpHeaders.Authorization, "Bearer ${apiKey}") }
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    suspend fun convertPdf(
        pdf: Path,
        targetDir: Path,
        options: Options
    ): OcrResult {
        return try {
            IoUtil.ensureDir(targetDir)
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
                mdFile = IoUtil.writeText(mdTarget, joinMarkdown(pageMarkdowns, options.combinePages), options.overwritePolicy)
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
            OcrResult(markdownFile = null, jsonFile = null, imageFiles = emptyList(), createdFiles = emptyList(), error = e.message ?: "Unknown error")
        }
    }

    private fun joinMarkdown(pages: List<String>, combine: Boolean): String =
        if (combine) pages.joinToString("\n\n") else pages.joinToString("\n\n")


    private suspend fun runMistralOCR(pdf: Path, options: Options): OcrResponse {
        if (options.apiKey.isBlank()) throw Exception("API key is empty")
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
        if (apiKey.isBlank()) return TestResult(false, "API key is empty")

        val resp = client.get("$base/v1/models") {
                headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
            }

        val ok = resp.status.value in 200..299

        return if (ok) TestResult(true, "Connection OK ${resp.status.value}") else TestResult(false, "HTTP error ${resp.status.value} ${resp.body<String>()}")
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PdfOcrService = project.service()
        val base = "https://api.mistral.ai"
    }
}
