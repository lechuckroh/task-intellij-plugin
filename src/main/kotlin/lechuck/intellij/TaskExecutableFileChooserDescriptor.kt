package lechuck.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptor

class TaskExecutableFileChooserDescriptor : FileChooserDescriptor(true, false, false, false, false, false) {
    init {
        title = "Select Task Executable"
        withFileFilter { file ->
            val filename = file.name
            "task".equals(filename, ignoreCase = true) || "task.exe".equals(filename, ignoreCase = true)
        }
    }
}