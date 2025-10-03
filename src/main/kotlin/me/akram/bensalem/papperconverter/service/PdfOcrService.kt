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
import me.akram.bensalem.papperconverter.data.request.OcrRequest
import me.akram.bensalem.papperconverter.data.response.FilesListResponse
import me.akram.bensalem.papperconverter.data.response.OcrResponse
import me.akram.bensalem.papperconverter.data.response.SignedUrlResponse
import me.akram.bensalem.papperconverter.data.response.UploadResponse
import me.akram.bensalem.papperconverter.settings.PdfOcrSettingsState
import me.akram.bensalem.papperconverter.util.IoUtil
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class PdfOcrService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): PdfOcrService = project.service()
        val base = "https://api.mistral.ai"
        val apiKey = "Vj7JjrteJn5CsGkrSLnlTQCM66EMyBVj"
    }

    private val log = Logger.getInstance(PdfOcrService::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
        }
    }

    data class Options(
        val includeImages: Boolean,
        val combinePages: Boolean,
        val overwritePolicy: PdfOcrSettingsState.OverwritePolicy,
        val apiKey: String
    )

    data class Result(
        val markdownFile: Path?,
        val imageFiles: List<Path>,
        val createdFiles: List<Path>,
        val error: String? = null
    )

    data class TestResult(val ok: Boolean, val message: String)


    suspend fun getAllFiles(): FilesListResponse =
        client.get("$base/v1/files") {
            headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
        }.body()

    suspend fun uploadFile(pdf: Path) : UploadResponse = client.post("${base}/v1/files") {
        headers { append(HttpHeaders.Authorization, "Bearer ${apiKey}") }
        val file = pdf.toFile()
        setBody(MultiPartFormDataContent(formData {
            append("purpose", "ocr")
            append("file", file.readBytes(), Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
            })
        }))
    }.body()

    suspend fun signUrl(id: String) : SignedUrlResponse = client.get("${base}/v1/files/$id/url"){
        headers { append(HttpHeaders.Authorization, "Bearer ${apiKey}") }
    }.body()

    suspend fun ocr(request : OcrRequest): OcrResponse {
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
    ): Result {
        return try {
            IoUtil.ensureDir(targetDir)
            val ocrResponse: OcrResponse = runMistralOCR(pdf, options)
            val pageMarkdowns = ocrResponse.pages.map { it.markdown }

            val imagesToWrite : List<Pair<String, ByteArray>> =
                ocrResponse.pages
                    .map { page -> page.images }
                    .flatten()
                    .filter { it.imageBase64 != null }
                    .map { it.id to it.toByteArray()!! }

            val stem = pdf.fileName.toString().substringBeforeLast('.')


            val mdTarget = targetDir.resolve("$stem.md")
            val created = mutableListOf<Path>()
            val mdFile = IoUtil.writeText(mdTarget, joinMarkdown(pageMarkdowns, options.combinePages), options.overwritePolicy)
            if (mdFile != null) created.add(mdFile)

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

            Result(markdownFile = mdFile, imageFiles = imageFiles, createdFiles = created)
        } catch (e: Exception) {
            log.warn("PDF OCR failed for $pdf", e)
            Result(markdownFile = null, imageFiles = emptyList(), createdFiles = emptyList(), error = e.message ?: "Unknown error")
        }
    }

    private fun joinMarkdown(pages: List<String>, combine: Boolean): String =
        if (combine) pages.joinToString("\n\n") else pages.joinToString("\n\n")


    private suspend fun runMistralOCR(pdf: Path, options: Options): OcrResponse {
        if (options.apiKey.isBlank()) throw Exception("API key is empty")
        return try {
                val upload: UploadResponse = uploadFile(pdf)
                val document: SignedUrlResponse = signUrl(upload.id)
                val request = OcrRequest(
                    model = "mistral-ocr-latest",
                    document = OcrRequest.Document(type = "document_url", documentUrl = document.url),
                    includeImageBase64 = true
                )
                ocr(request)
            } catch (e: Exception) {
                log.warn("Mistral OCR call failed", e)
                throw e
            } finally {
                client.close()
            }
    }


    suspend fun testConnection(apiKey: String): TestResult {
        if (apiKey.isBlank()) return TestResult(false, "API key is empty")
        return try {
            val ok = try {
                    val resp = client.get("https://api.mistral.ai/v1/models") {
                        headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                    }
                    resp.status.value in 200..299
                } finally {
                    client.close()
                }

            if (ok) TestResult(true, "Connection OK") else TestResult(false, "HTTP error")
        } catch (e: Exception) {
            TestResult(false, e.message ?: "Unknown error")
        }
    }
}
