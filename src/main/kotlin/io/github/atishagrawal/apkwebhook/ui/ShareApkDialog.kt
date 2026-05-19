package io.github.atishagrawal.apkwebhook.ui

import io.github.atishagrawal.apkwebhook.model.BranchInfo
import io.github.atishagrawal.apkwebhook.model.ShareRequest
import io.github.atishagrawal.apkwebhook.service.GitService
import io.github.atishagrawal.apkwebhook.service.GradleVariantService
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import io.github.atishagrawal.apkwebhook.util.JiraIdExtractor
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.SwingConstants

/**
 * Dialog driven by [ShareApkAction]. Collects a [ShareRequest] from the user — the heavy
 * lifting (worktree / gradle / upload / chat) happens in the action after `showAndGet()` returns
 * true.
 *
 * UX decisions:
 *  - Branch picker is a button that opens a [JBPopupFactory] chooser with
 *    [setNamerForFiltering] — same UX as Android Studio's "Git Branches" popup
 *    (filtered list, not highlight-only). Recent branches are prefixed with a star.
 *  - Build-variant combo enumerates `:app:assemble<Variant>` tasks from the IDE's
 *    cached Gradle sync via [GradleVariantService]. Falls back to the static
 *    settings list if the project hasn't been synced.
 *  - Selecting a branch auto-fills the JIRA ticket field IF the user hasn't manually
 *    edited it yet. Manual edits (including manual clears) freeze that field.
 *  - Validation is non-blocking on the JIRA field: a malformed value is a warning, not a block.
 */
class ShareApkDialog(
    private val project: Project,
    private val gitService: GitService,
    private val settings: ApkWebhookSettings.State,
) : DialogWrapper(project, true) {

    private val variantService = GradleVariantService(project)

    private val branchPickerButton = JButton("Pick a branch").apply {
        horizontalAlignment = SwingConstants.LEFT
        horizontalTextPosition = SwingConstants.LEFT
        icon = AllIcons.General.ArrowDown
        addActionListener { openBranchPicker() }
    }
    private val variantCombo = ComboBox<String>()
    private val messageField = JBTextField()
    private val jiraTicketField = JBTextField().apply {
        emptyText.text = "e.g. PROJ-1234 (optional)"
    }
    private val filenameField = JBTextField().apply {
        emptyText.text = "optional override"
    }

    private var branches: List<BranchInfo> = emptyList()
    private var selectedBranch: BranchInfo? = null

    /** True while the JIRA field reflects the auto-extracted value (so we can keep auto-filling). */
    private var jiraIsAutoFilled = true

    init {
        title = "Share APK"
        isResizable = true
        init()
        populateBranches()
        populateBuildVariants()
        wireJiraManualEditListener()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Branch:"), branchPickerButton, 1, false)
            .addLabeledComponent(JBLabel("Build variant:"), variantCombo, 1, false)
            .addLabeledComponent(JBLabel("Message:"), messageField, 1, false)
            .addLabeledComponent(JBLabel("JIRA ticket:"), jiraTicketField, 1, false)
            .addLabeledComponent(JBLabel("Filename:"), filenameField, 1, false)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = messageField

    override fun doValidate(): ValidationInfo? {
        val branch = selectedBranch
        if (branch == null || branch.displayName.isBlank()) {
            return ValidationInfo("Pick a branch", branchPickerButton)
        }
        val variant = (variantCombo.selectedItem as? String)?.trim().orEmpty()
        if (variant.isBlank()) {
            return ValidationInfo("Pick a build variant", variantCombo)
        }
        if (messageField.text.isBlank()) {
            return ValidationInfo("Message is required", messageField)
        }
        val ticket = jiraTicketField.text.trim()
        if (ticket.isNotBlank() && !ticket.matches(Regex("[A-Z][A-Z0-9]+-\\d+"))) {
            // Warning only — does not block submit.
            return ValidationInfo("Looks unusual for a JIRA id; submit anyway?", jiraTicketField).asWarning()
        }
        return null
    }

    fun getShareRequest(): ShareRequest {
        val branch = selectedBranch
            ?: error("ShareApkDialog.getShareRequest() called with no branch selected")
        val variantOrTask = (variantCombo.selectedItem as? String).orEmpty().trim()
        // Variants are bare names ("devDebug"); legacy settings entries may be full task paths
        // (":app:assembleDevDebug"). Detect by the colon prefix and translate when needed so
        // the build phase always receives a Gradle task path.
        val gradleTask = if (variantOrTask.contains(":")) variantOrTask
        else variantService.variantToTask(variantOrTask)
        // Return the bare branch name (e.g. "release/1"), NOT the remote ref ("origin/release/1").
        // Downstream services prepend "origin/" themselves when invoking git so the user-facing
        // metadata (chat card, BuildMeta) stays clean and git commands stay valid.
        return ShareRequest(
            branch = branch.displayName,
            gradleTask = gradleTask,
            message = messageField.text.trim(),
            jiraTicket = jiraTicketField.text.trim().takeIf { it.isNotEmpty() },
            filenameOverride = filenameField.text.trim().takeIf { it.isNotEmpty() },
        )
    }

    // --- internals ---------------------------------------------------------

    private fun openBranchPicker() {
        if (branches.isEmpty()) {
            // Empty list = nothing to pick. Surface the issue via the field's validation.
            return
        }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(branches)
            .setRenderer(BranchInfoRenderer())
            .setNamerForFiltering { it.displayName }
            .setFilterAlwaysVisible(true)
            .setTitle("Select branch (${branches.size})")
            .setItemChosenCallback { info -> applyBranchSelection(info) }
            .createPopup()
        popup.showUnderneathOf(branchPickerButton)
    }

    private fun applyBranchSelection(info: BranchInfo) {
        selectedBranch = info
        branchPickerButton.text = if (info.isRecent) "★ ${info.displayName}" else info.displayName
        // Auto-fill JIRA from the branch — only if the user hasn't manually edited it yet.
        if (jiraIsAutoFilled) {
            val extracted = JiraIdExtractor.extract(info.displayName).orEmpty()
            jiraTicketField.text = extracted
            jiraIsAutoFilled = true
        }
    }

    private fun populateBranches() {
        branches = runCatching { gitService.listBranches() }.getOrDefault(emptyList())

        val currentRef = runCatching { gitService.currentBranchRemoteRef() }.getOrNull()
        val initial = branches.firstOrNull { it.remoteRef == currentRef }
            ?: branches.firstOrNull { it.isRecent }
            ?: branches.firstOrNull()
        if (initial != null) applyBranchSelection(initial)
    }

    private fun populateBuildVariants() {
        val enumerated = variantService.listVariants()
        val items: List<String> = if (enumerated.isNotEmpty()) {
            enumerated
        } else {
            // Fall back to legacy settings list when the project hasn't been Gradle-synced.
            (listOf(settings.defaultGradleTask) + settings.additionalGradleTasks)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
        variantCombo.removeAllItems()
        items.forEach { variantCombo.addItem(it) }

        // Prefer the variant matching the user's default-task setting if present,
        // otherwise fall back to "debug", then first item.
        val defaultVariant = settings.defaultGradleTask
            .removePrefix(":app:assemble")
            .replaceFirstChar(Char::lowercase)
        val preferred = items.firstOrNull { it.equals(defaultVariant, ignoreCase = true) }
            ?: items.firstOrNull { it.equals("debug", ignoreCase = true) }
            ?: items.firstOrNull()
        if (preferred != null) variantCombo.selectedItem = preferred
    }

    private fun wireJiraManualEditListener() {
        // Any human edit to the ticket field flips off auto-fill mode (so future branch
        // selections leave the field alone). We detect "auto-fill in progress" by
        // comparing against the current branch's extraction.
        jiraTicketField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            private fun userEdit() {
                val expected = selectedBranch?.let { JiraIdExtractor.extract(it.displayName) }.orEmpty()
                jiraIsAutoFilled = jiraTicketField.text == expected
            }

            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = userEdit()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = userEdit()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = userEdit()
        })
    }

    /** Renders [BranchInfo] with a star prefix for recent branches. */
    private class BranchInfoRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val info = value as? BranchInfo
            text = when {
                info == null -> ""
                info.isRecent -> "★ ${info.displayName}"
                else -> info.displayName
            }
            return component
        }
    }
}
