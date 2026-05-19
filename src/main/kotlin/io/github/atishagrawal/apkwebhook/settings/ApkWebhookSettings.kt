package io.github.atishagrawal.apkwebhook.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-scoped persistent settings for the APK Webhook plugin.
 *
 * Stored as `apk-webhook.xml` under the IDE config dir. The Chat webhook URL is the only
 * sensitive value and lives in [ApkWebhookSecrets] (PasswordSafe), not here.
 */
@Service(Service.Level.APP)
@State(name = "ApkWebhookSettings", storages = [Storage("apk-webhook.xml")])
class ApkWebhookSettings : PersistentStateComponent<ApkWebhookSettings.State> {

    data class State(
        var serverUploadBase: String = "http://localhost:3001",
        var serverInstallBase: String = "http://localhost:3001",
        var serverDownloadBase: String = "http://localhost:8082",
        var serverBuildsPage: String = "http://localhost:8082/",
        var defaultGradleTask: String = ":app:assembleDebug",
        var additionalGradleTasks: MutableList<String> = mutableListOf(),
        var appName: String = "app",
        var uploader: String = System.getProperty("user.name") ?: "unknown",
        var envLabel: String = "",
        var jiraBaseUrl: String = "https://your-org.atlassian.net/browse/",
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(s: State) {
        state = s
    }

    companion object {
        fun getInstance(): ApkWebhookSettings = service()
    }
}
