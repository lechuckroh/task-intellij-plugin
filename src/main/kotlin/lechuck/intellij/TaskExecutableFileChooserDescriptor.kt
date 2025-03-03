package lechuck.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.VirtualFile

class TaskExecutableFileChooserDescriptor : FileChooserDescriptor(true, false, false, false, false, false) {
    init {
        title = "Select Task Executable"
    }

    override fun isFileSelectable(file: VirtualFile?): Boolean {
        if (file == null || file.isDirectory) {
            return false
        }

        val filename = file.name
        return ("task".equals(filename, ignoreCase = true) || "task.exe".equals(filename, ignoreCase = true))
    }
}