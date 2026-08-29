package lechuck.intellij.discovery

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Single entry point for discovering the tasks in a Taskfile, shared by every consumer so there is
 * exactly one place that knows how to read a Taskfile rather than a parser per consumer.
 *
 * ## The two sources answer different questions
 *
 * [TaskCliDiscovery] and [TaskYamlDiscovery] are not a preference and a fallback for the same
 * question; they are authorities over different ones, and where they disagree the answer depends on
 * what is being asked.
 *
 * - **[TaskCliDiscovery] owns what is runnable.** Only Task itself knows the full picture: it
 *   downloads, verifies and caches *remote* includes, it expands templates and `sh:` variables in
 *   an include path, and it computes `up_to_date`. When something is about to be executed, or shown
 *   as executable, this is the authority.
 * - **[TaskYamlDiscovery] owns what a Taskfile says.** It sees the `internal: true` tasks the CLI
 *   never reports, it can follow local `includes:`, it answers from an editor buffer that has not
 *   been saved, and it costs no subprocess -- so it is what editor-side features (references,
 *   navigation, inspections) can use, since those run under the read lock where waiting on a
 *   process is forbidden.
 *
 * Two rules keep that split honest. **Execution follows the CLI**: a name the CLI does not report
 * is not offered as runnable. And **the parser never guesses**: an include it cannot resolve on the
 * files alone is reported as unresolved (see [UnresolvedInclude]), never silently dropped, so a
 * caller can say "this part could not be read" instead of showing a list that is quietly short.
 *
 * The CLI is tried first, on every call -- a missing `task` binary fails fast, and caching that
 * absence would miss someone installing it mid-session.
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
            is CliOutcome.Success ->
                DiscoveryResult(
                    outcome.tasks,
                    warning = null,
                    // The include graph the caller needs for invalidation: an included file
                    // edited on disk has to invalidate the entry cached under the file that
                    // includes it. Both halves are needed -- where the CLI says each task was
                    // defined covers files the parser cannot reach (remote, templated), and the
                    // parser covers files that contribute no task of their own and so are absent
                    // from the CLI's output entirely.
                    sourceFiles =
                        outcome.tasks.mapNotNull { it.taskfilePath.ifEmpty { null } }.toSet() +
                            TaskYamlDiscovery.sourceFilesOf(project, file) +
                            file.path,
                    unresolvedIncludes = emptyList(),
                )
            is CliOutcome.Failure -> {
                val parsed = TaskYamlDiscovery.discoverDetailed(project, file)
                DiscoveryResult(
                    parsed.tasks,
                    outcome.reason,
                    parsed.sourceFiles + file.path,
                    parsed.unresolvedIncludes,
                )
            }
        }
}

internal data class DiscoveryResult(
    val tasks: List<DiscoveredTask>,
    val warning: CliFailureReason?,
    /** Every Taskfile that contributed a task, the one asked about included. */
    val sourceFiles: Set<String> = emptySet(),
    /** Includes that could not be followed -- always empty when the CLI answered. */
    val unresolvedIncludes: List<UnresolvedInclude> = emptyList(),
)
