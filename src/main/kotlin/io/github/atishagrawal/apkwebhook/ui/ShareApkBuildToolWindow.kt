package io.github.atishagrawal.apkwebhook.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import java.io.OutputStream

/**
 * Lazily registers the "APK Share Build" tool window and exposes a ConsoleView
 * that the share pipeline can stream Gradle output into.
 *
 * Each share invocation removes the previous tab (which disposes the old ConsoleView
 * via the registered child disposer) and creates a fresh one — single-tab UX, no leaks.
 */
object ShareApkBuildToolWindow {
    private const val TOOL_WINDOW_ID = "APK Share Build"
    private const val TAB_TITLE = "Build"

    /**
     * Open (registering if needed) the tool window, replace any prior tab with a fresh
     * ConsoleView, and return that console. Must be invoked on EDT.
     */
    fun openFreshConsole(project: Project, headerLine: String): ConsoleView {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.getToolWindow(TOOL_WINDOW_ID) ?: manager.registerToolWindow(
            RegisterToolWindowTask(
                id = TOOL_WINDOW_ID,
                anchor = ToolWindowAnchor.BOTTOM,
                canCloseContent = true,
                icon = AllIcons.Toolwindows.ToolWindowBuild,
            )
        )

        val cm = toolWindow.contentManager
        cm.findContent(TAB_TITLE)?.let { cm.removeContent(it, true) }

        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val content = ContentFactory.getInstance().createContent(console.component, TAB_TITLE, false)
        // Tying the console's lifecycle to the Content guarantees the EditorImpl is
        // released when the tab is removed (next run) or the project closes.
        content.setDisposer(console)
        cm.addContent(content)
        cm.setSelectedContent(content)
        toolWindow.activate(null, false)

        console.print("$headerLine\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        return console
    }

    /**
     * OutputStream sink that pipes Gradle stdout/stderr into [console]. Thread-safe —
     * ConsoleView.print itself queues writes onto EDT, so writes from the background
     * Task.Backgroundable thread are fine.
     */
    fun asStream(
        console: ConsoleView,
        type: ConsoleViewContentType = ConsoleViewContentType.NORMAL_OUTPUT,
    ): OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            console.print(b.toChar().toString(), type)
        }

        override fun write(buf: ByteArray, off: Int, len: Int) {
            if (len > 0) console.print(String(buf, off, len), type)
        }
    }
}
