package io.github.atishagrawal.apkwebhook.ui

import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSecrets
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page registered under `Tools → APK Webhook`. Edits both [ApkWebhookSettings.State]
 * (plain text fields) and the webhook URL secret stored in PasswordSafe via [ApkWebhookSecrets].
 *
 * `additionalGradleTasks` is rendered as a multi-line text area (one task per line) for
 * simplicity — power users with multiple flavors can list every variant they need.
 */
class ApkWebhookConfigurable : Configurable {

    private var rootPanel: JPanel? = null

    private val serverUploadBaseField = JBTextField()
    private val serverInstallBaseField = JBTextField()
    private val serverDownloadBaseField = JBTextField()
    private val serverBuildsPageField = JBTextField()
    private val defaultGradleTaskField = JBTextField()
    private val additionalGradleTasksArea = JBTextArea(4, 40).apply {
        lineWrap = false
    }
    private val appNameField = JBTextField()
    private val uploaderField = JBTextField()
    private val envLabelField = JBTextField()
    private val jiraBaseUrlField = JBTextField()
    private val chatWebhookUrlField = JBPasswordField()

    override fun getDisplayName(): String = "APK Webhook"

    override fun createComponent(): JComponent {
        val tasksScroll = JBScrollPane(additionalGradleTasksArea).apply {
            border = JBUI.Borders.empty()
        }

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server upload base URL:"), serverUploadBaseField, 1, false)
            .addLabeledComponent(JBLabel("Server install base URL:"), serverInstallBaseField, 1, false)
            .addLabeledComponent(JBLabel("Server download base URL:"), serverDownloadBaseField, 1, false)
            .addLabeledComponent(JBLabel("Server builds page URL:"), serverBuildsPageField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Default Gradle task:"), defaultGradleTaskField, 1, false)
            .addLabeledComponent(
                JBLabel("Additional Gradle tasks (one per line):"),
                tasksScroll,
                1,
                true,
            )
            .addSeparator()
            .addLabeledComponent(JBLabel("App name:"), appNameField, 1, false)
            .addLabeledComponent(JBLabel("Uploader:"), uploaderField, 1, false)
            .addLabeledComponent(JBLabel("Environment label:"), envLabelField, 1, false)
            .addLabeledComponent(JBLabel("JIRA base URL:"), jiraBaseUrlField, 1, false)
            .addSeparator()
            .addLabeledComponent(JBLabel("Chat webhook URL (PasswordSafe):"), chatWebhookUrlField, 1, false)
            .addComponent(
                JBLabel("<html><i>Webhook must match https://chat.googleapis.com/v1/spaces/&hellip;</i></html>"),
                0,
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel

        rootPanel = panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val state = ApkWebhookSettings.getInstance().state
        val savedWebhook = ApkWebhookSecrets.getInstance().getWebhookUrl().orEmpty()
        return serverUploadBaseField.text != state.serverUploadBase ||
            serverInstallBaseField.text != state.serverInstallBase ||
            serverDownloadBaseField.text != state.serverDownloadBase ||
            serverBuildsPageField.text != state.serverBuildsPage ||
            defaultGradleTaskField.text != state.defaultGradleTask ||
            parseTasks(additionalGradleTasksArea.text) != state.additionalGradleTasks ||
            appNameField.text != state.appName ||
            uploaderField.text != state.uploader ||
            envLabelField.text != state.envLabel ||
            jiraBaseUrlField.text != state.jiraBaseUrl ||
            String(chatWebhookUrlField.password) != savedWebhook
    }

    override fun apply() {
        val settings = ApkWebhookSettings.getInstance()
        val state = settings.state
        state.serverUploadBase = serverUploadBaseField.text.trim()
        state.serverInstallBase = serverInstallBaseField.text.trim()
        state.serverDownloadBase = serverDownloadBaseField.text.trim()
        state.serverBuildsPage = serverBuildsPageField.text.trim()
        state.defaultGradleTask = defaultGradleTaskField.text.trim()
        state.additionalGradleTasks = parseTasks(additionalGradleTasksArea.text).toMutableList()
        state.appName = appNameField.text.trim()
        state.uploader = uploaderField.text.trim()
        state.envLabel = envLabelField.text.trim()
        state.jiraBaseUrl = jiraBaseUrlField.text.trim()

        val webhook = String(chatWebhookUrlField.password).trim()
        ApkWebhookSecrets.getInstance().setWebhookUrl(webhook.takeIf { it.isNotEmpty() })
    }

    override fun reset() {
        val state = ApkWebhookSettings.getInstance().state
        serverUploadBaseField.text = state.serverUploadBase
        serverInstallBaseField.text = state.serverInstallBase
        serverDownloadBaseField.text = state.serverDownloadBase
        serverBuildsPageField.text = state.serverBuildsPage
        defaultGradleTaskField.text = state.defaultGradleTask
        additionalGradleTasksArea.text = state.additionalGradleTasks.joinToString("\n")
        appNameField.text = state.appName
        uploaderField.text = state.uploader
        envLabelField.text = state.envLabel
        jiraBaseUrlField.text = state.jiraBaseUrl
        // PasswordSafe.get() can be slow; settings dialog already runs on EDT so this is best-effort.
        // TODO(perf): if this blocks noticeably, move to a pooled-thread load + populate via invokeLater.
        chatWebhookUrlField.text = ApkWebhookSecrets.getInstance().getWebhookUrl().orEmpty()
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    /**
     * Lightweight validation surfaced by the platform when the user hits Apply.
     * Currently advisory only — actual hard validation can be wired by adding a
     * `ConfigurableWithCustomValidation` later if needed.
     */
    @Suppress("unused")
    fun validate(): ValidationInfo? {
        val webhook = String(chatWebhookUrlField.password).trim()
        if (webhook.isNotEmpty() && !webhook.startsWith("https://chat.googleapis.com/v1/spaces/")) {
            return ValidationInfo(
                "Webhook must start with https://chat.googleapis.com/v1/spaces/",
                chatWebhookUrlField,
            )
        }
        return null
    }

    private fun parseTasks(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
