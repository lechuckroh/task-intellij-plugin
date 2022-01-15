package lechuck.intellij

import com.intellij.execution.configurations.ConfigurationTypeBase

class TaskRunConfigurationType :
    ConfigurationTypeBase(ID, "Taskfile", "Taskfile run configuration type", TaskPluginIcons.Task) {
    companion object {
        const val ID = "TaskRunConfiguration"
    }

    init {
        addFactory(TaskConfigurationFactory(this))
    }
}