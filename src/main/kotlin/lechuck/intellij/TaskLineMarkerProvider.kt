package lechuck.intellij

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLKeyValue

class TaskLineMarkerProvider : RunLineMarkerContributor() {
    companion object {
        val TASKFILE_PATTERN = Regex("taskfile(?:\\.dist)?\\.ya?ml", RegexOption.IGNORE_CASE)
        private val LOG = Logger.getInstance(TaskLineMarkerProvider::class.java)

        /**
         * Returns the Taskfile run configuration for [taskName] — reusing the one registered by an
         * earlier run, or creating and registering it the first time — and points it at [taskName]
         * inside [taskfilePath].
         *
         * The lookup is scoped to [TaskRunConfigurationType], so an unrelated configuration of
         * another type that happens to share the name is never picked up.
         *
         * Returns null if the platform ever hands back settings whose configuration is not a
         * [TaskRunConfiguration]; the type-scoped lookup makes that unreachable in practice. Throws
         * [AssertionError] if [TaskRunConfigurationType] is not registered at all, which cannot
         * happen while this contributor is producing gutter icons.
         */
        internal fun prepareConfiguration(
            project: Project,
            taskName: String,
            taskfilePath: String,
        ): RunnerAndConfigurationSettings? {
            val runManager = RunManager.getInstance(project)
            // the platform owns the instance declared in plugin.xml, and configurations are matched
            // to it by identity, so a freshly constructed TaskRunConfigurationType would never
            // match an existing configuration and would duplicate one on every run
            val configurationType =
                ConfigurationTypeUtil.findConfigurationType(TaskRunConfigurationType::class.java)

            val configurationName = "Task: $taskName"
            // scoped to our own type: findConfigurationByName(String) matches on name alone and
            // would return a same-named configuration of any other type
            val existingConfiguration =
                runManager.findConfigurationByTypeAndName(configurationType, configurationName)

            val configuration =
                existingConfiguration
                    ?: runManager.createConfiguration(
                        configurationName,
                        TaskRunConfigurationType::class.java,
                    )

            // narrowing the lookup above already rules out a foreign type, so this is a guard
            // rather than an expected branch
            val runConfig = configuration.configuration as? TaskRunConfiguration ?: return null
            runConfig.task = taskName
            runConfig.filename = taskfilePath

            if (existingConfiguration == null) {
                runManager.addConfiguration(configuration)
            }

            return configuration
        }
    }

    override fun getInfo(element: PsiElement): Info? {
        // Only process YAML files named: Taskfile.yml, taskfile.yml, Taskfile.yaml, taskfile.yaml,
        // Taskfile.dist.yml, taskfile.dist.yml, Taskfile.dist.yaml, taskfile.dist.yaml
        val file = element.containingFile ?: return null
        if (!file.name.matches(TASKFILE_PATTERN)) {
            return null
        }
        val virtualFile = file.virtualFile ?: return null

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

        // Check if we're on the key element itself
        if (element != keyValue.key) {
            return null
        }

        // This is a task definition, create a run action
        val taskName = keyValue.keyText
        val icon = AllIcons.Actions.Execute
        val actions = arrayOf(TaskRunAction(taskName, element.project, virtualFile.path))
        return Info(icon, actions, { "Run Task: $taskName" })
    }

    private class TaskRunAction(
        private val taskName: String,
        private val project: Project,
        private val taskfilePath: String,
    ) : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val configuration = prepareConfiguration(project, taskName, taskfilePath) ?: return

            // must run after the configuration is registered, and on the EDT
            RunManager.getInstance(project).selectedConfiguration = configuration

            try {
                val executor =
                    ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)
                if (executor != null) {
                    ExecutionEnvironmentBuilder.create(executor, configuration).buildAndExecute()
                }
            } catch (ex: ExecutionException) {
                LOG.warn("Failed to execute task: $taskName", ex)
            }
        }
    }
}
