package me.akram.bensalem.papperconverter.data.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FilesListResponse(val data: List<FileItem>) {
    @Serializable
    data class FileItem(
        val id: String,
        @SerialName("created_at") val createdAt: Long,
        val filename: String,
        val purpose: String,
        @SerialName("sample_type") val sampleType: String,
        @SerialName("num_lines") val numLines: Int,
        val mimetype: String,
        val source: String,
        val signature: String
    )

    val total: Int
        get() = data.size
}
