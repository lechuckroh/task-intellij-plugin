package lechuck.intellij

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

class TaskLineMarkerProvider : RunLineMarkerContributor() {
    companion object {
        val TASKFILE_PATTERN = Regex("taskfile(?:\\.dist)?\\.ya?ml", RegexOption.IGNORE_CASE)
    }

    override fun getInfo(element: PsiElement): Info? {
        // Only process YAML files named: Taskfile.yml, taskfile.yml, Taskfile.yaml, taskfile.yaml,
        // Taskfile.dist.yml, taskfile.dist.yml, Taskfile.dist.yaml, taskfile.dist.yaml
        val file = element.containingFile
        if (!file.name.matches(TASKFILE_PATTERN)) {
            return null
        }

        // We want to match only the key element of a task
        if (element.parent !is YAMLKeyValue) {
            return null
        }
        val keyValue = element.parent as YAMLKeyValue

        // Check if this key is directly under the tasks section
        val tasksSection = keyValue.parent?.parent
        if (tasksSection !is YAMLKeyValue || tasksSection.keyText != "tasks") {
            return null
        }

        // Check if the value is a mapping (has child elements)
        if (keyValue.value !is YAMLMapping) {
            return null
        }

        // Check if we're on the key element itself
        if (element != keyValue.key) {
            return null
        }

        // This is a task definition, create a run action
        val taskName = keyValue.keyText
        val icon = AllIcons.Actions.Execute
        val actions = arrayOf(TaskRunAction(taskName, element.project, file.virtualFile.path))
        return Info(icon, actions, { "Run Task: $taskName" })
    }

    private class TaskRunAction(
        private val taskName: String,
        private val project: Project,
        private val taskfilePath: String,
    ) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val runManager = RunManagerImpl.getInstanceImpl(project)
            val configurationType = TaskRunConfigurationType()

            val configurationName = "Task: $taskName"
            val existingConfiguration = runManager.findConfigurationByName(configurationName)

            val configuration =
                if (existingConfiguration != null) {
                    existingConfiguration
                } else {
                    val factory = configurationType.configurationFactories[0]
                    runManager.createConfiguration(configurationName, factory)
                }

            val runConfig = configuration.configuration as TaskRunConfiguration
            runConfig.task = taskName
            runConfig.filename = taskfilePath

            if (existingConfiguration == null) {
                runManager.addConfiguration(configuration)
            }

            runManager.selectedConfiguration = configuration

            try {
                val executor =
                    ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
                if (executor != null) {
                    ExecutionEnvironmentBuilder.create(executor, configuration).buildAndExecute()
                }
            } catch (ex: ExecutionException) {
                ex.printStackTrace()
            }
        }
    }
}
