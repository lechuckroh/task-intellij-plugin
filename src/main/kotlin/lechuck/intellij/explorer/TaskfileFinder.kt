package lechuck.intellij.explorer

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor

/**
 * Finds every Taskfile in the project: `Taskfile.yml`, `taskfile.yml`, `Taskfile.yaml`,
 * `taskfile.yaml`, and each of those again with a `.dist` suffix -- the 8 literal casings task
 * itself recognizes. (Narrower than [lechuck.intellij.TaskLineMarkerProvider.TASKFILE_PATTERN],
 * which is fully case-insensitive and so also matches casings like `TASKFILE.YML` that task itself
 * would not.)
 *
 * Queries [FilenameIndex] for all 8 literal names in one case-sensitive batch rather than once
 * case-insensitively: the case-insensitive overload scans every file name in the project to find
 * matches, while a case-sensitive query is a direct index lookup -- cheap regardless of how many
 * unrelated files the project has.
 */
internal object TaskfileFinder {
    private val NAMES =
        listOf("Taskfile", "taskfile")
            .flatMap { base ->
                listOf("yml", "yaml").flatMap { ext -> listOf("$base.$ext", "$base.dist.$ext") }
            }
            .toSet()

    /**
     * Whether [name] is one of the 8 literal names [findAll] looks for -- used to filter VFS change
     * events down to ones that could plausibly add, remove, or rename a Taskfile (see
     * [TaskExplorerPanel]'s `BulkFileListener`).
     */
    fun isRecognizedName(name: String): Boolean = name in NAMES

    /**
     * Returns an empty list while the project is still indexing rather than throwing -- the caller
     * is expected to retry once smart mode resumes (see [TaskExplorerPanel]'s `DumbService`
     * listener).
     *
     * Wraps [FilenameIndex] access in its own [ReadAction] rather than relying on an ambient one:
     * the tree this feeds ([TaskExplorerRootNode]) is built by an
     * [com.intellij.util.concurrency.Invoker] with no read action of its own (see
     * [TaskExplorerPanel]), since [TaskfileGroupNode] needs to shell out to `task` on the same
     * thread without holding the read lock while it does.
     */
    fun findAll(project: Project): List<VirtualFile> =
        try {
            ReadAction.computeBlocking<List<VirtualFile>, IndexNotReadyException> {
                val scope = GlobalSearchScope.projectScope(project)
                val found = mutableListOf<VirtualFile>()
                FilenameIndex.processFilesByNames(
                    NAMES,
                    true,
                    scope,
                    null,
                    Processor {
                        found.add(it)
                        true
                    },
                )
                found
            }
        } catch (e: IndexNotReadyException) {
            emptyList()
        }
}
