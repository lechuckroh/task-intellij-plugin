package lechuck.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptor

class TaskfileFileChooserDescriptor : FileChooserDescriptor(true, false, false, false, false, false) {
    init {
        title = "Select Taskfile"
        withFileFilter { file ->
            val ext = file.extension
            "yaml".equals(ext, ignoreCase = true) || "yml".equals(ext, ignoreCase = true)
        }
    }
}