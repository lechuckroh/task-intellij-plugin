package lechuck.intellij

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathMacros
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.FixedSizeButton
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import lechuck.intellij.vars.VariablesComponent

class TaskRunConfigurationEditor(private val project: Project) :
    SettingsEditor<TaskRunConfiguration>() {

    private companion object {
        private val LOG = Logger.getInstance(TaskRunConfigurationEditor::class.java)
    }

    private val taskExecutableField = TextFieldWithBrowseButton()
    private val filenameField = TextFieldWithBrowseButton()
    private val taskCompletionProvider =
        TextFieldWithAutoCompletion.StringsCompletionProvider(emptyList(), TaskPluginIcons.Task)
    private val taskField = TextFieldWithAutoCompletion(project, taskCompletionProvider, true, "")
    private val argumentsField = ExpandableTextField()
    private val envVarsComponent = EnvironmentVariablesComponent(project)
    private val varsComponent = VariablesComponent()
    private val workingDirectoryField = TextFieldWithBrowseButton()
    private val ptyCheckBox = JBCheckBox("Run in terminal (PTY)")
    private val mapper =
        ObjectMapper(YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val completionRequestId = AtomicLong(0)

    private val panel: JPanel by lazy {
        FormBuilder.createFormBuilder()
            .setAlignLabelOnRight(false)
            .setHorizontalGap(UIUtil.DEFAULT_HGAP)
            .setVerticalGap(UIUtil.DEFAULT_VGAP)
            .addLabeledComponent("Task executable", taskExecutableField)
            .addLabeledComponent("Taskfile", filenameField)
            .addLabeledComponent("Task", taskField)
            .addComponent(LabeledComponent.create(argumentsField, "CLI arguments"))
            .addLabeledComponent(
                "Working directory",
                createComponentWithMacroBrowse(workingDirectoryField),
            )
            .addComponent(envVarsComponent)
            .addComponent(varsComponent)
            .addComponent(ptyCheckBox)
            .panel
    }

    init {
        taskExecutableField.addBrowseFolderListener(
            TextBrowseFolderListener(TaskExecutableFileChooserDescriptor(), project)
        )

        filenameField.addBrowseFolderListener(
            TextBrowseFolderListener(TaskfileFileChooserDescriptor(), project)
        )

        filenameField.textField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    updateTargetCompletion(filenameField.text)
                }
            }
        )

        workingDirectoryField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                project,
            )
        )
    }

    /**
     * Rebuilds the Task field's completion list from [filename].
     *
     * Every keystroke in the Taskfile field starts a lookup, and pooled threads finish in any
     * order, so a lookup over a large file could publish its list after a later lookup over a small
     * one and leave the previous file's tasks in the completion list. Each call claims a request id
     * and only the newest claim is allowed to publish.
     */
    private fun updateTargetCompletion(filename: String) {
        val requestId = completionRequestId.incrementAndGet()
        val file = resolveTaskfile(filename)
        if (file != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val psiFile =
                    ReadAction.computeBlocking<PsiFile?, RuntimeException> {
                        PsiManager.getInstance(project).findFile(file)
                    }
                val results = psiFile?.let { findTasks(it) } ?: emptyList()

                SwingUtilities.invokeLater {
                    if (requestId == completionRequestId.get()) {
                        taskCompletionProvider.setItems(results)
                    }
                }
            }
        } else {
            // no id check here: both callers of this method run on the EDT, so claiming the id
            // above has already stopped every lookup still in flight from publishing
            taskCompletionProvider.setItems(emptyList())
        }
    }

    /**
     * Locates the Taskfile the completion list is built from, or null when [filename] names no
     * existing file.
     *
     * The path is macro-expanded first. `$PROJECT_DIR$/Taskfile.yml` is a valid entry — running the
     * configuration expands it too, see [TaskRunConfiguration.buildCommandLine] — but no such path
     * exists on disk, so looking up the literal text would find nothing and leave the completion
     * list empty with no visible error.
     *
     * Expansion is as far as the resemblance to the run path goes. A path still relative after
     * expansion is resolved here against the IDE process's working directory, while running the
     * configuration resolves it against the project root. A relative entry therefore draws its
     * completions from the wrong file, or from none. Expanding macros neither caused that nor fixes
     * it.
     *
     * An empty [filename] expands to itself and resolves to that same process working directory
     * rather than to null, as it did before. It stays harmless: a directory has no [PsiFile], so
     * the caller ends up with an empty list.
     */
    internal fun resolveTaskfile(filename: String): VirtualFile? {
        // expandPath only returns null for a null input; filename is never null, but the
        // platform still types the result nullable, so fall back to the unexpanded path.
        val expandedPath = PathMacroManager.getInstance(project).expandPath(filename) ?: filename
        return LocalFileSystem.getInstance().findFileByPath(expandedPath)
    }

    /**
     * Parses the task names out of [file].
     *
     * The text comes from the PSI rather than from the file's bytes, so a task typed into the
     * editor but not saved yet still shows up in the completion list. The PSI follows the document
     * as of the last commit, which the platform performs between keystrokes.
     */
    internal fun findTasks(file: PsiFile): Collection<String> {
        return try {
            val text = ReadAction.computeBlocking<String, RuntimeException> { file.text }
            val taskfile: Taskfile = mapper.readValue(text, Taskfile::class.java)
            taskfile.tasks?.keys ?: emptyList()
        } catch (e: Exception) {
            LOG.warn("Failed to parse Taskfile: ${file.name}", e)
            emptyList()
        }
    }

    override fun createEditor() = panel

    override fun resetEditorFrom(cfg: TaskRunConfiguration) {
        taskExecutableField.text = cfg.taskPath
        filenameField.text = cfg.filename
        taskField.text = cfg.task
        argumentsField.text = cfg.arguments
        envVarsComponent.envData = cfg.environmentVariables
        varsComponent.varData = cfg.variables
        workingDirectoryField.text = cfg.workingDirectory
        ptyCheckBox.isSelected = cfg.pty

        updateTargetCompletion(cfg.filename)
    }

    override fun applyEditorTo(cfg: TaskRunConfiguration) {
        cfg.taskPath = taskExecutableField.text
        cfg.filename = filenameField.text
        cfg.task = taskField.text
        cfg.arguments = argumentsField.text
        cfg.environmentVariables = envVarsComponent.envData
        cfg.variables = varsComponent.varData
        cfg.workingDirectory = workingDirectoryField.text
        cfg.pty = ptyCheckBox.isSelected
    }

    private fun createComponentWithMacroBrowse(
        textAccessor: TextFieldWithBrowseButton
    ): JComponent {
        val button = FixedSizeButton(textAccessor)
        button.icon = AllIcons.Actions.ListFiles
        button.addActionListener {
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(PathMacros.getInstance().userMacroNames.toList())
                .setItemChosenCallback { textAccessor.text = "$$it$" }
                .setMovable(false)
                .setResizable(false)
                .createPopup()
                .showUnderneathOf(button)
        }
        return JPanel(BorderLayout()).apply {
            add(textAccessor, BorderLayout.CENTER)
            add(button, BorderLayout.EAST)
        }
    }
}
