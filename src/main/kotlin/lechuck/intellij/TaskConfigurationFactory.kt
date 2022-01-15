package lechuck.intellij

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project

class TaskConfigurationFactory(private val runCfgType: TaskRunConfigurationType) : ConfigurationFactory(runCfgType) {

    override fun getId() = TaskRunConfigurationType.ID

    override fun getName(): String = runCfgType.displayName

    override fun createTemplateConfiguration(project: Project) = TaskRunConfiguration(project, this, "Task")
}