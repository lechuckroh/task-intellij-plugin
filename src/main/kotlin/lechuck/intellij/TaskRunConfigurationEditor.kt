package lechuck.intellij

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathMacros
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.FixedSizeButton
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.TextFieldWithAutoCompletion
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import lechuck.intellij.vars.VariablesComponent

class TaskRunConfigurationEditor(private val project: Project) :
    SettingsEditor<TaskRunConfiguration>() {
    private val taskExecutableField = TextFieldWithBrowseButton()
    private val filenameField = TextFieldWithBrowseButton()
    private val taskCompletionProvider =
        TextFieldWithAutoCompletion.StringsCompletionProvider(emptyList(), TaskPluginIcons.Task)
    private val taskField = TextFieldWithAutoCompletion(project, taskCompletionProvider, true, "")
    private val argumentsField = ExpandableTextField()
    private val envVarsComponent = EnvironmentVariablesComponent()
    private val varsComponent = VariablesComponent()
    private val workingDirectoryField = TextFieldWithBrowseButton()
    private val mapper =
        ObjectMapper(YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

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

    private fun updateTargetCompletion(filename: String) {
        val file = LocalFileSystem.getInstance().findFileByPath(filename)
        if (file != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val psiFile =
                    ReadAction.compute<PsiFile?, RuntimeException> {
                        PsiManager.getInstance(project).findFile(file)
                    }
                val results = psiFile?.let { findTasks(it) } ?: emptyList()

                SwingUtilities.invokeLater { taskCompletionProvider.setItems(results) }
            }
        } else {
            taskCompletionProvider.setItems(emptyList())
        }
    }

    private fun findTasks(file: PsiFile): Collection<String> {
        return try {
            file.virtualFile.inputStream.use { `is` ->
                val taskfile: Taskfile = mapper.readValue(`is`, Taskfile::class.java)
                taskfile.tasks?.keys ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
