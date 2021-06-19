package lechuck.intellij

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.StoredProperty

class TaskRunConfigurationOptions : RunConfigurationOptions() {
    private val taskFilename: StoredProperty<String?> = string("").provideDelegate(this, "taskFilename")

    fun getTaskFilename(): String {
        return taskFilename.getValue(this)!!
    }

    fun setTaskFilename(filename: String) {
        taskFilename.setValue(this, filename)
    }
}
