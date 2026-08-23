package lechuck.intellij.explorer

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * The Task Explorer's own view toggles, persisted per-project across restarts via
 * [PropertiesComponent] (the same lightweight key-value store IntelliJ's own tool windows use for
 * this kind of UI state -- not worth a dedicated `PersistentStateComponent` for two booleans).
 */
@Service(Service.Level.PROJECT)
internal class TaskExplorerViewSettings(private val project: Project) {
    /**
     * Whether `internal: true` tasks -- never reported by
     * [lechuck.intellij.discovery.TaskCliDiscovery] regardless of flags -- are additionally
     * discovered by scanning each Taskfile's own YAML directly (see
     * [lechuck.intellij.discovery.TaskYamlDiscovery.discoverInternalOnly]) and shown in the tree.
     */
    var showInternalTasks: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(SHOW_INTERNAL_KEY, false)
        set(value) =
            PropertiesComponent.getInstance(project).setValue(SHOW_INTERNAL_KEY, value, false)

    /** Whether a task's `desc:` shows up as its row's secondary text. */
    var showDescriptions: Boolean
        get() = PropertiesComponent.getInstance(project).getBoolean(SHOW_DESC_KEY, true)
        set(value) = PropertiesComponent.getInstance(project).setValue(SHOW_DESC_KEY, value, true)

    companion object {
        private const val SHOW_INTERNAL_KEY = "lechuck.taskExplorer.showInternalTasks"
        private const val SHOW_DESC_KEY = "lechuck.taskExplorer.showDescriptions"

        fun getInstance(project: Project): TaskExplorerViewSettings = project.service()
    }
}
