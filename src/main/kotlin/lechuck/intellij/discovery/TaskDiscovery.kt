package lechuck.intellij.discovery

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Single entry point for discovering the tasks in a Taskfile, shared by every consumer (currently
 * the Task run configuration's autocomplete; a gutter icon and a tool window are planned) so there
 * is exactly one place that knows how to read a Taskfile rather than a parser per consumer.
 *
 * Tries [TaskCliDiscovery] first, on every call -- a missing `task` binary fails fast, and caching
 * that absence would miss someone installing it mid-session. Only the CLI reads [file] from disk
 * the way running `task` itself would, which is what lets it resolve `includes:`; whenever it can't
 * answer cleanly, [TaskYamlDiscovery] falls back to the editor's own unsaved text instead, since
 * there is no disk-only requirement to uphold once the CLI is out of the picture.
 */
object TaskDiscovery {
    /**
     * [taskExecutable] mirrors [lechuck.intellij.TaskRunConfiguration.taskPath] -- pass it along
     * when discovering tasks for a specific run configuration so its custom `task` binary (if any)
     * is the one consulted, same as running that configuration would use.
     */
    fun discover(
        project: Project,
        file: VirtualFile,
        taskExecutable: String = "",
    ): List<DiscoveredTask> = discoverDetailed(project, file, taskExecutable).tasks

    /**
     * Same as [discover], but also reports *why* the CLI didn't answer when it didn't -- for a
     * consumer (the tool window's [lechuck.intellij.explorer.TaskfileGroupNode]) that wants to tell
     * a user "task isn't installed" or "this Taskfile doesn't parse" apart from "this Taskfile
     * genuinely has no tasks", rather than showing the same empty list for all three.
     */
    internal fun discoverDetailed(
        project: Project,
        file: VirtualFile,
        taskExecutable: String = "",
    ): DiscoveryResult =
        when (val outcome = TaskCliDiscovery.discover(file.path, taskExecutable)) {
            is CliOutcome.Success -> DiscoveryResult(outcome.tasks, warning = null)
            is CliOutcome.Failure ->
                DiscoveryResult(TaskYamlDiscovery.discover(project, file), outcome.reason)
        }
}

internal data class DiscoveryResult(
    val tasks: List<DiscoveredTask>,
    val warning: CliFailureReason?,
)
