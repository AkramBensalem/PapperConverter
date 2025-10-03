package me.akram.bensalem.papperconverter.data.response

import kotlinx.serialization.Serializable

@Serializable
data class UploadResponse(
    val id: String,
    val signature: String
)