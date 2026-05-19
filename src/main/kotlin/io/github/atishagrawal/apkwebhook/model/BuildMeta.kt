package io.github.atishagrawal.apkwebhook.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuildMeta(
    @SerialName("version") val version: String = "",
    @SerialName("build") val build: String = "0",
    @SerialName("branch") val branch: String,
    @SerialName("env") val env: String = "QA",
    @SerialName("uploader") val uploader: String,
    @SerialName("notes") val notes: String,
    @SerialName("filename") val filename: String,
    @SerialName("jiraTicket") val jiraTicket: String? = null,
)
