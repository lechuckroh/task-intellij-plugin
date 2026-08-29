package lechuck.intellij.explorer

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.ui.treeStructure.SimpleNode
import lechuck.intellij.TaskLineMarkerProvider
import lechuck.intellij.TaskPluginIcons
import lechuck.intellij.discovery.CliFailureReason
import lechuck.intellij.discovery.DiscoveredTask
import lechuck.intellij.discovery.TaskYamlDiscovery
import lechuck.intellij.discovery.UnresolvedInclude
import lechuck.intellij.discovery.UnresolvedIncludeReason

/**
 * Invisible tree root: its children are one [TaskfileGroupNode] per Taskfile [TaskfileFinder] finds
 * in the project.
 *
 * [onDiscoveryReady] is threaded down to every [TaskfileGroupNode] so a background discovery
 * completing (see [TaskDiscoveryCache]) can ask [TaskExplorerPanel] to refresh the tree, without
 * this node needing a reference back to the panel itself.
 */
internal class TaskExplorerRootNode(
    private val project: Project,
    private val onDiscoveryReady: () -> Unit,
) : SimpleNode(project) {
    override fun getChildren(): Array<SimpleNode> =
        TaskfileFinder.findAll(project)
            .sortedBy { it.path }
            .map { TaskfileGroupNode(project, it, onDiscoveryReady) }
            .toTypedArray()

    override fun getEqualityObjects(): Array<Any> = arrayOf(project)
}

/**
 * One Taskfile's group in the tree, labeled with its path relative to the project root. Its
 * children are the tasks it has, discovered the same way
 * [lechuck.intellij.TaskRunConfigurationEditor]'s autocomplete does.
 *
 * Neither [update] nor [getChildren] runs discovery directly -- both go through
 * [TaskDiscoveryCache], which runs the actual `task` process on its own bounded thread pool rather
 * than the tree's single-threaded [com.intellij.util.concurrency.Invoker]. [update] never blocks on
 * a cache miss: a project with hundreds of Taskfiles renders every row's plain label immediately
 * and lets the warning (if any) show up once the pool gets to it, rather than making the whole list
 * wait on all of them serially. [getChildren] does block on a miss, since an explicit expand is a
 * one-off action for a single file that can afford to wait on it.
 */
internal class TaskfileGroupNode(
    private val project: Project,
    private val file: VirtualFile,
    private val onDiscoveryReady: () -> Unit,
) : SimpleNode(project) {
    // Widened from the inherited `protected` so tests in this package can drive it directly instead
    // of only through the tree UI.
    public override fun update(presentation: PresentationData) {
        val base = project.basePath
        presentation.presentableText =
            if (base != null && file.path.startsWith("$base/")) file.path.removePrefix("$base/")
            else file.path
        presentation.setIcon(AllIcons.FileTypes.Yaml)

        val cache = TaskDiscoveryCache.getInstance(project)
        val cached = cache.cachedResult(file)
        if (cached != null) {
            // A CLI failure only speaks for the whole file when nothing was found at all; an
            // unresolved include is worth saying either way, since the list is short by however
            // many tasks that include would have brought in. Reporting them rather than dropping
            // them is the point of the parser tracking them at all (see TaskDiscovery).
            presentation.locationString =
                (if (cached.tasks.isEmpty()) cached.warning.toDisplayText() else null)
                    ?: cached.unresolvedIncludes.toDisplayText()
        } else {
            cache.ensureDiscovered(file, onDiscoveryReady)
        }
    }

    override fun getChildren(): Array<SimpleNode> {
        val tasks = TaskDiscoveryCache.getInstance(project).awaitResult(file).tasks
        val withInternal =
            if (TaskExplorerViewSettings.getInstance(project).showInternalTasks) {
                // Not cached in TaskDiscoveryCache: this is a plain YAML scan, not a `task`
                // subprocess call, so it's cheap enough to redo on every getChildren() rather than
                // needing the same treatment as the CLI-backed result above.
                //
                // Deduplicated by name against `tasks`, which never contains an internal task
                // whichever backend answered -- the CLI does not report them, and the parser
                // filters them out. The guard costs nothing and keeps this row-building honest if
                // either of those ever changes.
                val existingNames = tasks.mapTo(mutableSetOf()) { it.name }
                tasks +
                    TaskYamlDiscovery.discoverInternalOnly(project, file).filter {
                        it.name !in existingNames
                    }
            } else {
                tasks
            }
        return withInternal.map { TaskNode(project, it, file.path) }.toTypedArray()
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(file)
}

/**
 * One line naming the includes that could not be followed, or null when every include resolved.
 * Kept short -- this is a tree row's grey suffix, not a report -- so several unresolved includes
 * are counted rather than listed.
 */
private fun List<UnresolvedInclude>.toDisplayText(): String? {
    if (isEmpty()) return null
    val first = first()
    val reason =
        when (first.reason) {
            UnresolvedIncludeReason.REMOTE -> "remote include not read"
            UnresolvedIncludeReason.DYNAMIC -> "include path is dynamic"
            UnresolvedIncludeReason.MISSING -> "included file not found"
        }
    return if (size == 1) "${first.namespace}: $reason"
    else "${first.namespace}: $reason (+${size - 1} more)"
}

/**
 * Text for a Taskfile group with zero tasks -- null means the CLI (or its YAML fallback) genuinely
 * found none, which is worth distinguishing from the CLI not having run cleanly at all (an
 * uninstalled binary or an unparsable Taskfile also produce zero tasks, but for a different
 * reason).
 */
private fun CliFailureReason?.toDisplayText(): String? =
    when (this) {
        null -> null
        CliFailureReason.CLI_UNAVAILABLE -> "task CLI not found on PATH"
        CliFailureReason.NO_TASKFILE -> "Taskfile not found"
        CliFailureReason.PARSE_FAILED -> "Failed to parse Taskfile"
        CliFailureReason.OTHER -> "Failed to list tasks"
    }

/**
 * A leaf task. [groupTaskfilePath] is the *group's* Taskfile -- the one to pass `task` via
 * `--taskfile` to run [task]'s name (including its namespace prefix, if any) correctly -- which is
 * not necessarily [DiscoveredTask.taskfilePath]: for a task that came from an `includes:` entry,
 * that field instead points at the included file task itself resolved the task to, which is what
 * [navigate] uses instead, since "go to definition" means the file the task is actually written in.
 */
internal class TaskNode(
    private val project: Project,
    private val task: DiscoveredTask,
    private val groupTaskfilePath: String,
) : SimpleNode(project), Navigatable {
    internal val name: String
        get() = task.name

    internal fun run() {
        TaskLineMarkerProvider.runTask(project, task.name, groupTaskfilePath)
    }

    /**
     * Opens [DiscoveredTask.taskfilePath] at [DiscoveredTask.line] (task's own 1-indexed line,
     * [OpenFileDescriptor] wants 0-indexed) -- falls back to just opening the file with no
     * particular line when there is no line (an internal task found via
     * [lechuck.intellij.discovery.TaskYamlDiscovery.discoverInternalOnly] doesn't have one).
     */
    override fun navigate(requestFocus: Boolean) {
        val path = task.taskfilePath.ifEmpty { groupTaskfilePath }
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val line = task.line?.let { (it - 1).coerceAtLeast(0) }
        val descriptor =
            if (line != null) OpenFileDescriptor(project, file, line, 0)
            else OpenFileDescriptor(project, file)
        descriptor.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean =
        LocalFileSystem.getInstance()
            .findFileByPath(task.taskfilePath.ifEmpty { groupTaskfilePath }) != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun getChildren(): Array<SimpleNode> = NO_CHILDREN

    override fun update(presentation: PresentationData) {
        presentation.presentableText = task.name
        presentation.setIcon(TaskPluginIcons.Task)
        val showDescriptions = TaskExplorerViewSettings.getInstance(project).showDescriptions
        presentation.locationString =
            listOfNotNull(
                    task.desc.takeIf { it.isNotEmpty() && showDescriptions },
                    "(internal)".takeIf { task.isInternal },
                )
                .joinToString(" ")
                .ifEmpty { null }
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(task.name)
}
