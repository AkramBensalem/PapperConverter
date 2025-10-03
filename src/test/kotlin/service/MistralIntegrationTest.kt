package service

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.*
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.akram.bensalem.papperconverter.service.PdfOcrService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration test that calls Mistral OCR using a real API key and a real PDF.
 *
 * To run locally, export MISTRAL_API_KEY to your environment (or pass -DMISTRAL_API_KEY=...)
 * The test will be skipped automatically if the key is not provided.
 */
class MistralIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class UploadResp(val id: String)

    @Serializable
    private data class SignedUrlResp(val url: String)

    @Serializable
    private data class OcrRequest(
        val model: String,
        val document: Document,
        @SerialName("include_image_base64") val includeImageBase64: Boolean
    ) {
        @Serializable
        data class Document(
            val type: String,
            @SerialName("document_url") val documentUrl: String
        )
    }

    @Test
    fun `mistral ocr returns markdown and json files`() {
        val base = "https://api.mistral.ai"
        val apiKey = "PaxK0NtlSya55tqbD4CIzBxCkbFaOiPv"

        val pdf: Path = Path.of("totest/gentile2025human.pdf").toAbsolutePath()
        assumeTrue(Files.exists(pdf), "Test PDF not found at $pdf")
        val outDir = Files.createTempDirectory("mistral-ocr-test")


        val client = HttpClient(OkHttp) { install(ContentNegotiation) { json(json) } }
        try {
            val upload = runBlocking {
                client.post("$base/v1/files") {
                    headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                    val file = pdf.toFile()
                    setBody(MultiPartFormDataContent(formData {
                        append("purpose", "ocr")
                        append("file", file.readBytes(), Headers.build {
                            append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                            append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                        })
                    }))
                }.body<UploadResp>()
            }



            val signed = runBlocking {
                client.get("$base/v1/files/${upload.id}/signed-url") {
                    headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                }.body<SignedUrlResp>()
            }

            val ocr = runBlocking {
                val req = OcrRequest(
                    model = "mistral-ocr-latest",
                    document = OcrRequest.Document(type = "document_url", documentUrl = signed.url),
                    includeImageBase64 = false
                )
                client.post("$base/v1/ocr") {
                    headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                    contentType(ContentType.Application.Json)
                    setBody(req)
                }.body<PdfOcrService.OcrResponse>()
            }

            val stem = pdf.fileName.toString().substringBeforeLast('.')
            val mdPath = outDir.resolve("$stem.md")
            val jsonPath = outDir.resolve("$stem.json")

            val markdown = ocr.pages.joinToString("\n\n") { it.markdown }
            Files.writeString(mdPath, markdown)
            Files.writeString(jsonPath, json.encodeToString(PdfOcrService.OcrResponse.serializer(), ocr))

            assertTrue(Files.exists(mdPath) && Files.size(mdPath) > 0, "Markdown file should be created and non-empty: $mdPath")
            assertTrue(Files.exists(jsonPath) && Files.size(jsonPath) > 0, "JSON file should be created and non-empty: $jsonPath")
        } finally {
            client.close()
        }
    }
}
