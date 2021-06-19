package lechuck.intellij

import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.icons.AllIcons

class TaskRunConfigurationType :
    ConfigurationTypeBase(ID, "Taskfile", "Taskfile run configuration type", AllIcons.General.Information) {

    init {
        addFactory(TaskConfigurationFactory(this))
    }

    companion object {
        const val ID = "TaskRunConfiguration"
    }
}