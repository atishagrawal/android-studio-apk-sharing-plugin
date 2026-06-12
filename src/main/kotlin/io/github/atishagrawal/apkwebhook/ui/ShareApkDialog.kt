package io.github.atishagrawal.apkwebhook.ui

import io.github.atishagrawal.apkwebhook.model.BranchInfo
import io.github.atishagrawal.apkwebhook.model.ShareRequest
import io.github.atishagrawal.apkwebhook.service.CommitLogService
import io.github.atishagrawal.apkwebhook.service.CommitSummary
import io.github.atishagrawal.apkwebhook.service.GitService
import io.github.atishagrawal.apkwebhook.service.GradleVariantService
import io.github.atishagrawal.apkwebhook.settings.ApkWebhookSettings
import io.github.atishagrawal.apkwebhook.util.JiraIdExtractor
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val CHANGES_PLACEHOLDER = "Commits load here when you pick a branch — edit freely"

/**
 * Dialog driven by [ShareApkAction]. Collects a [ShareRequest] from the user.
 *
 * UX decisions:
 *  - Branch picker: [JBPopupFactory] chooser with `setNamerForFiltering` (AS-style filtered list).
 *  - Build-variant combo: enumerated from the IDE's cached Gradle sync via [GradleVariantService].
 *  - **Message** is an optional, multi-line free-text note (what to test, caveats).
 *  - **Changes** is a multi-line field pre-filled with the branch's unique commit subjects and
 *    fully editable; it drives the card's Changes section. The prefill is async (git runs off the
 *    EDT) and freezes the moment the user edits it — same model as the JIRA field below.
 *  - Selecting a branch auto-fills the JIRA ticket (until manually edited).
 *  - Enter inserts a newline in the text areas; Ctrl+Enter submits the dialog.
 */
class ShareApkDialog(
    private val project: Project,
    private val gitService: GitService,
    private val settings: ApkWebhookSettings.State,
) : DialogWrapper(project, true) {

    private val variantService = GradleVariantService(project)
    private val commitLogService = CommitLogService(project)

    /** Captured once: the branch currently checked out, so prefill can use local HEAD for it. */
    private val currentBranchName: String? = runCatching { gitService.currentBranchName() }.getOrNull()

    private val branchPickerButton = JButton("Pick a branch").apply {
        horizontalAlignment = SwingConstants.LEFT
        horizontalTextPosition = SwingConstants.LEFT
        icon = AllIcons.General.ArrowDown
        addActionListener { openBranchPicker() }
    }
    private val variantCombo = ComboBox<String>()
    private val messageArea = JBTextArea(3, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null)
        setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null)
        emptyText.text = "Optional note — what to test, caveats…"
    }
    private val changesArea = JBTextArea(6, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null)
        setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null)
        emptyText.text = CHANGES_PLACEHOLDER
    }
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

    /** True while the Changes field reflects the auto-prefilled commit list. */
    private var changelogIsAutoFilled = true
    private var lastAutoFilledChangelog = ""
    private val prefillRequestId = AtomicInteger()

    init {
        title = "Share APK"
        isResizable = true
        init()
        listOf(messageArea, changesArea).forEach { area ->
            area.registerKeyboardAction(
                { clickDefaultButton() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_FOCUSED,
            )
        }
        wireJiraManualEditListener()
        wireChangesManualEditListener()
        populateBranches()
        populateBuildVariants()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Branch:"), branchPickerButton, 1, false)
            .addLabeledComponent(JBLabel("Build variant:"), variantCombo, 1, false)
            .addLabeledComponent(JBLabel("Message:"), JBScrollPane(messageArea), 1, true)
            .addLabeledComponent(JBLabel("Changes:"), JBScrollPane(changesArea), 1, true)
            .addLabeledComponent(JBLabel("JIRA ticket:"), jiraTicketField, 1, false)
            .addLabeledComponent(JBLabel("Filename:"), filenameField, 1, false)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = messageArea

    override fun doValidate(): ValidationInfo? {
        val branch = selectedBranch
        if (branch == null || branch.displayName.isBlank()) {
            return ValidationInfo("Pick a branch", branchPickerButton)
        }
        val variant = (variantCombo.selectedItem as? String)?.trim().orEmpty()
        if (variant.isBlank()) {
            return ValidationInfo("Pick a build variant", variantCombo)
        }
        // Message is optional — no required check.
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
        val gradleTask = if (variantOrTask.contains(":")) variantOrTask
        else variantService.variantToTask(variantOrTask)
        return ShareRequest(
            branch = branch.displayName,
            gradleTask = gradleTask,
            message = messageArea.text.trim(),
            jiraTicket = jiraTicketField.text.trim().takeIf { it.isNotEmpty() },
            filenameOverride = filenameField.text.trim().takeIf { it.isNotEmpty() },
            changelogText = changesArea.text,
        )
    }

    // --- internals ---------------------------------------------------------

    private fun openBranchPicker() {
        if (branches.isEmpty()) return
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
            jiraTicketField.text = JiraIdExtractor.extract(info.displayName).orEmpty()
            jiraIsAutoFilled = true
        }
        prefillChangesFor(info)
    }

    /**
     * Loads the branch's unique commit subjects on a pooled thread and drops them into the
     * Changes field on the EDT — only while the field is still auto-filled and this is the
     * latest request. Captures the dialog modality first; otherwise the `invokeLater` would be
     * deferred until the modal dialog closes (default modality is non-modal).
     */
    private fun prefillChangesFor(info: BranchInfo) {
        if (!settings.prefillChangelogFromCommits || !changelogIsAutoFilled) return
        val id = prefillRequestId.incrementAndGet()
        val isCurrent = info.displayName == currentBranchName
        val modality = rootPane?.let { ModalityState.stateForComponent(it) } ?: ModalityState.current()
        changesArea.emptyText.text = "Loading commits…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val summary = runCatching {
                commitLogService.commitsForShare(info.displayName, isCurrent, settings.commitLogLimit)
            }.getOrDefault(CommitSummary(emptyList(), 0))
            ApplicationManager.getApplication().invokeLater({
                if (id != prefillRequestId.get() || isDisposed) return@invokeLater
                if (changelogIsAutoFilled) {
                    val text = buildList {
                        addAll(summary.subjects)
                        if (summary.extraCount > 0) add("…and ${summary.extraCount} more")
                    }.joinToString("\n")
                    // Set the baseline BEFORE the text so the document listener sees a match and
                    // keeps `changelogIsAutoFilled == true` for this programmatic fill.
                    lastAutoFilledChangelog = text
                    changesArea.text = text
                }
                changesArea.emptyText.text = CHANGES_PLACEHOLDER
            }, modality)
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
            (listOf(settings.defaultGradleTask) + settings.additionalGradleTasks)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        }
        variantCombo.removeAllItems()
        items.forEach { variantCombo.addItem(it) }

        val defaultVariant = settings.defaultGradleTask
            .removePrefix(":app:assemble")
            .replaceFirstChar(Char::lowercase)
        val preferred = items.firstOrNull { it.equals(defaultVariant, ignoreCase = true) }
            ?: items.firstOrNull { it.equals("debug", ignoreCase = true) }
            ?: items.firstOrNull()
        if (preferred != null) variantCombo.selectedItem = preferred
    }

    private fun wireJiraManualEditListener() {
        jiraTicketField.document.addDocumentListener(object : DocumentListener {
            private fun userEdit() {
                val expected = selectedBranch?.let { JiraIdExtractor.extract(it.displayName) }.orEmpty()
                jiraIsAutoFilled = jiraTicketField.text == expected
            }

            override fun insertUpdate(e: DocumentEvent?) = userEdit()
            override fun removeUpdate(e: DocumentEvent?) = userEdit()
            override fun changedUpdate(e: DocumentEvent?) = userEdit()
        })
    }

    private fun wireChangesManualEditListener() {
        changesArea.document.addDocumentListener(object : DocumentListener {
            private fun userEdit() {
                changelogIsAutoFilled = changesArea.text == lastAutoFilledChangelog
            }

            override fun insertUpdate(e: DocumentEvent?) = userEdit()
            override fun removeUpdate(e: DocumentEvent?) = userEdit()
            override fun changedUpdate(e: DocumentEvent?) = userEdit()
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
