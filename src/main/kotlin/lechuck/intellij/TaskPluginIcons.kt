package lechuck.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

interface TaskPluginIcons {
    companion object {
        val Task: Icon = IconLoader.getIcon("/icons/task.png", TaskPluginIcons::class.java)
    }
}