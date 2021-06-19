package lechuck.intellij

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

class TaskConfigurationFactory(type: ConfigurationType): ConfigurationFactory(type) {
    override fun getId(): String {
        return TaskRunConfigurationType.ID
    }

    override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return TaskRunConfiguration(project, this, "Task")
    }

    override fun getOptionsClass(): Class<out BaseState> {
        return TaskRunConfigurationOptions::class.java
    }
}
