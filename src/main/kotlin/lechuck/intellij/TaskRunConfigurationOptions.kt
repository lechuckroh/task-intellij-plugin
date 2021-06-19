package lechuck.intellij

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.StoredProperty

class TaskRunConfigurationOptions : RunConfigurationOptions() {
    private val taskfile: StoredProperty<String?> = string("").provideDelegate(this, "taskfile")
    private val task: StoredProperty<String?> = string("").provideDelegate(this, "task")

    fun getTaskfile(): String {
        return taskfile.getValue(this) ?: ""
    }

    fun setTaskfile(filename: String) {
        taskfile.setValue(this, filename)
    }

    fun getTask(): String {
        return task.getValue(this) ?: ""
    }

    fun setTask(name: String) {
        task.setValue(this, name)
    }
}
