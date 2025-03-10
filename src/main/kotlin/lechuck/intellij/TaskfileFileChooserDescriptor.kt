package lechuck.intellij

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.VirtualFile

class TaskfileFileChooserDescriptor : FileChooserDescriptor(true, false, false, false, false, false) {
    init {
        title = "Select Taskfile"
    }

    override fun isFileSelectable(file: VirtualFile?): Boolean {
        if (file == null || file.isDirectory) {
            return false
        }

        val ext = file.extension
        return "yaml".equals(ext, ignoreCase = true) || "yml".equals(ext, ignoreCase = true)
    }
}