package lechuck.intellij.runanything

import com.intellij.ide.actions.runAnything.activity.RunAnythingCommandLineProvider
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon
import lechuck.intellij.TaskLineMarkerProvider
import lechuck.intellij.TaskPluginIcons
import lechuck.intellij.explorer.TaskDiscoveryCache
import lechuck.intellij.explorer.TaskfileFinder

/** What a Run Anything command line resolves to: the run configuration's own two fields. */
internal data class ResolvedTaskCommand(val task: String, val taskfilePath: String)

/**
 * Completes and runs `task <name>` from the Run Anything popup (Ctrl+Ctrl).
 *
 * Both halves reuse what already exists rather than adding a second way to do either: task names
 * come from the [lechuck.intellij.discovery.TaskDiscovery] seam (via [TaskDiscoveryCache], the same
 * cache the Task Explorer tool window reads), and running goes through
 * [TaskLineMarkerProvider.runTask] -- the same prepare-a-configuration-and-execute-it path the
 * gutter icon and the tool window use.
 *
 * The whole command line is passed through, not just a single name:
 * [lechuck.intellij.TaskRunConfiguration.task] is added to the command line with
 * `addParametersString`, so `task build test` runs both tasks and `task build --force` passes the
 * flag along, the same as typing that line in a terminal. What is typed is handed over *as written*
 * (see [resolveCommand]) rather than re-joined from the parsed tokens, since the platform parses
 * with `keepQuotes = true` and re-joining would escape those quotes a second time.
 *
 * The popup's "Choose context" dropdown (project / module / recent directory) is left as
 * [com.intellij.ide.actions.runAnything.activity.RunAnythingProviderBase] offers it, and
 * [com.intellij.ide.actions.runAnything.activity.RunAnythingProvider.EXECUTING_CONTEXT] is
 * deliberately ignored: this provider passes `--taskfile` explicitly, and
 * [lechuck.intellij.TaskRunConfiguration.buildCommandLine] derives the working directory from that
 * file, so a context directory would either be redundant or contradict the Taskfile the completed
 * name actually came from.
 */
internal class TaskRunAnythingProvider : RunAnythingCommandLineProvider() {
    override fun getHelpCommand(): String = "task"

    override fun getHelpCommandPlaceholder(): String = "task <task name...>"

    override fun getHelpDescription(): String = "Run a Taskfile task"

    override fun getHelpIcon(): Icon = TaskPluginIcons.Task

    // Non-null is what makes the popup show these as a completion group at all (see
    // RunAnythingCompletionGroup.createCompletionGroup).
    override fun getCompletionGroupTitle(): String = "Taskfile"

    override fun getHelpGroupTitle(): String = "Taskfile"

    override fun getIcon(value: String): Icon = TaskPluginIcons.Task

    /**
     * The base class matches every *prefix* of the help command too -- `t`, `ta`, `tas` all parse
     * as an empty `task` command line (see `RunAnythingCommandLineProvider`'s
     * `extractLeadingHelpPrefix`). That is unhelpful here in a way it is not for a provider reading
     * an in-memory model: [suggestCompletionVariants] kicks off discovery for every
     * not-yet-discovered Taskfile in the project, and a single `t` keystroke must not fan out one
     * `task` subprocess per Taskfile in a monorepo. Requiring the whole word first costs the user
     * nothing they were not already typing.
     *
     * Checks [getHelpCommand] only, while the base class also matches [getHelpCommandAliases]. That
     * is equivalent while there are no aliases, which there aren't; anyone adding one has to widen
     * this to `(listOf(helpCommand) + getHelpCommandAliases()).any(pattern::startsWith)` or the
     * alias will match the command line but complete nothing.
     */
    override fun getValues(dataContext: DataContext, pattern: String): List<String> {
        if (!pattern.startsWith(helpCommand)) return emptyList()
        return super.getValues(dataContext, pattern)
    }

    /**
     * Suggests every task name known across the project's Taskfiles, minus the ones already typed
     * on this command line (a task named twice on one `task` invocation runs once, so offering it
     * again is noise).
     *
     * Only *cached* discovery results are read here, never awaited: the platform calls this inside
     * a [com.intellij.openapi.application.ReadAction.nonBlocking] computation (see
     * `RunAnythingPopupUI.rebuildList`), and blocking on the `task` CLI while holding the read lock
     * is exactly what `OSProcessHandler` forbids -- the same constraint that shapes
     * [lechuck.intellij.explorer.TaskExplorerPanel]. A Taskfile that has not been discovered yet is
     * skipped after kicking discovery off on the cache's own thread pool, so the next keystroke's
     * rebuild has it. The popup rebuilds its list on every keystroke, which is what makes that
     * "skip now, ready shortly" behavior invisible in practice for anyone typing a name rather than
     * pasting one whole.
     */
    override fun suggestCompletionVariants(
        dataContext: DataContext,
        commandLine: CommandLine,
    ): Sequence<String> {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return emptySequence()
        return taskfilesByPreference(project)
            .asSequence()
            .flatMap { file -> cachedTaskNames(project, file).asSequence() }
            .distinct()
            .filter { it !in commandLine }
    }

    override fun run(dataContext: DataContext, commandLine: CommandLine): Boolean {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return false
        val resolved = resolveCommand(project, commandLine.parameters, commandLine.command)
        if (resolved == null) return false

        TaskLineMarkerProvider.runTask(project, resolved.task, resolved.taskfilePath)
        return true
    }

    companion object {
        /**
         * Decides what to run for a command line: [command] verbatim as the run configuration's
         * task field, against the Taskfile picked from [parameters].
         *
         * The Taskfile is the one whose cached discovery result declares the first parameter, since
         * that is the only token that could plausibly be a task name. Anything else -- a flag
         * (`task --list`), a variable assignment (`task VAR=1 build`), or a name whose Taskfile
         * simply has not been discovered yet (pasted, or picked from the recent-commands list on a
         * cold cache) -- falls back to the shallowest Taskfile, which is the same file `task`
         * itself would resolve from the project root. Falling back rather than refusing keeps this
         * off the alternative of awaiting discovery, which cannot be done here: `run` is called on
         * the EDT.
         *
         * Null only when the project has no Taskfile at all, or nothing was typed after `task` --
         * both of which the popup reports as a failed command.
         */
        internal fun resolveCommand(
            project: Project,
            parameters: List<String>,
            command: String,
        ): ResolvedTaskCommand? {
            if (command.isEmpty()) return null
            val taskfiles = taskfilesByPreference(project)
            val shallowest = taskfiles.firstOrNull() ?: return null
            // A non-empty command always parses to at least one parameter, so this is a total
            // function rather than a branch worth reaching for: it just spares the lookup below
            // from having to carry a nullable.
            val firstParameter =
                parameters.firstOrNull() ?: return ResolvedTaskCommand(command, shallowest.path)
            val taskfile =
                taskfiles.firstOrNull { file -> firstParameter in cachedTaskNames(project, file) }
                    ?: shallowest
            return ResolvedTaskCommand(command, taskfile.path)
        }

        /**
         * Project Taskfiles, shallowest first -- a name declared in more than one of them (a
         * monorepo with a per-module Taskfile plus a root one that `includes:` them, say) resolves
         * to the one closest to the project root, so the same input always runs the same task
         * rather than whichever file the index happened to return first. Two Taskfiles at the same
         * depth are ordered by path, which is a coin flip in meaning but a stable one -- the point
         * of the second key is only that repeating an input repeats the run.
         */
        private fun taskfilesByPreference(project: Project): List<VirtualFile> =
            TaskfileFinder.findAll(project)
                .sortedWith(compareBy({ it.path.count { c -> c == '/' } }, { it.path }))

        private fun cachedTaskNames(project: Project, file: VirtualFile): List<String> {
            val cache = TaskDiscoveryCache.getInstance(project)
            val cached = cache.cachedResult(file)
            if (cached == null) {
                cache.ensureDiscovered(file) {}
                return emptyList()
            }
            return cached.tasks.map { it.name }
        }
    }
}
