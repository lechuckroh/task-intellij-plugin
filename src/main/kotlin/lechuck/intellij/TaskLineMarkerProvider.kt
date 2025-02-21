package lechuck.intellij

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

class TaskLineMarkerProvider : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        // Only process YAML files named Taskfile.yml or taskfile.yml
        val file = element.containingFile
        if (!file.name.lowercase().matches(Regex("taskfile\\.ya?ml"))) {
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
        return Info(
            AllIcons.Actions.Execute,
            { "Run Task: $taskName" },
            TaskRunAction(taskName, element.project)
        )
    }

    private class TaskRunAction(
        private val taskName: String,
        private val project: Project
    ) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val runManager = RunManagerImpl.getInstanceImpl(project)
            val configurationType = TaskRunConfigurationType()

            // Try to find existing configuration first
            val configurationName = "Task: $taskName"
            val existingConfiguration = runManager.findConfigurationByName(configurationName)

            val configuration = if (existingConfiguration != null) {
                existingConfiguration
            } else {
                // Create new configuration
                val factory = configurationType.configurationFactories[0]
                runManager.createConfiguration(configurationName, factory)
            }

            // Configure the task
            val runConfig = configuration.configuration as TaskRunConfiguration
            runConfig.task = taskName

            // Set Taskfile path relative to project
            val projectPath = project.basePath
            if (projectPath != null) {
                runConfig.filename = "$projectPath/Taskfile.yml"
            }

            // Add to run manager if it's a new configuration
            if (existingConfiguration == null) {
                runManager.addConfiguration(configuration)
            }

            // Set as selected configuration
            runManager.selectedConfiguration = configuration

            // Execute the configuration
            try {
                val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
                if (executor != null) {
                    ExecutionEnvironmentBuilder.create(executor, configuration)?.buildAndExecute()
                }
            } catch (ex: ExecutionException) {
                // Handle execution error
                ex.printStackTrace()
            }
        }
    }
}