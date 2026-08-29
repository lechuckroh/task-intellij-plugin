package lechuck.intellij.explorer

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import lechuck.intellij.discovery.CliFailureReason
import lechuck.intellij.discovery.DiscoveryResult
import lechuck.intellij.discovery.TaskDiscovery
import org.jetbrains.annotations.TestOnly

/**
 * Caches [TaskDiscovery.discoverDetailed] results by Taskfile path, computed on a small bounded
 * thread pool rather than the tree's own single-threaded
 * [com.intellij.util.concurrency.Invoker.forBackgroundThreadWithoutReadAction] (see
 * [TaskExplorerPanel]). A project with hundreds of Taskfiles would otherwise serialize hundreds of
 * `task` subprocess calls one after another on that one thread -- both to render the initial list
 * of labels and to answer any single expand request queued behind them.
 *
 * Entries are invalidated by this service's own VFS listener (below), so every consumer gets the
 * same freshness guarantee; [invalidate] is also callable directly for a caller that knows an entry
 * is stale for some other reason.
 */
@Service(Service.Level.PROJECT)
internal class TaskDiscoveryCache(private val project: Project) : Disposable {
    private val results = ConcurrentHashMap<String, CompletableFuture<DiscoveryResult>>()

    // Reverse edge of the include graph: contributing file -> the roots whose cached result was
    // built from it. Without this, editing an included file leaves the including file's entry
    // cached and stale, and no name filter could find it -- `includes:` takes an arbitrary path,
    // so an included Taskfile can be called anything at all.
    private val includedBy = ConcurrentHashMap<String, MutableSet<String>>()

    // Bumped by every invalidation. A discovery that was already running when its input changed
    // must not have its now-stale result cached: each op on the maps above is atomic on its own,
    // but "compute, then record, then publish" is not, so without this an edit landing mid-flight
    // is simply lost and the stale entry survives until the next edit of either file.
    private val generation = AtomicLong()
    private val executor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("Task Explorer Discovery", CONCURRENCY)

    init {
        // A stale result must not survive an edit, delete, or rename of the Taskfile it came from
        // (an edited Taskfile keeps the same path across the event that reports it changing, so
        // nothing else would evict it).
        //
        // Owned by the cache rather than by a consumer: this used to live in TaskExplorerPanel's
        // own VFS listener, which only exists once that panel has been constructed -- i.e. once
        // the tool window has actually been opened. Every other consumer (the Run Anything
        // provider) was left reading results that could never go stale-checked in a session where
        // the user never opened the tool window.
        //
        // Filtered by filename for the same reason the panel filters its own refresh: a project
        // has plenty of unrelated file traffic, and only one of the 8 recognized Taskfile names
        // can be a key here in the first place.
        project.messageBus
            .connect(this)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        events.forEach { event ->
                            // The name filter is an optimization for the common case; a file that
                            // is known to have contributed to some cached result is invalidated
                            // whatever it is called, since an `includes:` target need not be named
                            // like a Taskfile at all.
                            if (
                                TaskfileFinder.isRecognizedName(
                                    event.path.substringAfterLast('/')
                                ) || includedBy.containsKey(event.path)
                            ) {
                                invalidate(event.path)
                            }
                            if (event is VFilePropertyChangeEvent && event.isRename) {
                                invalidate(event.oldPath)
                            }
                        }
                    }
                },
            )
    }

    /** The cached result for [file], or null if discovery for it hasn't finished yet. */
    fun cachedResult(file: VirtualFile): DiscoveryResult? {
        val future = results[file.path] ?: return null
        return if (future.isDone) future.resultOrFallback() else null
    }

    /**
     * Ensures discovery for [file] is running (or already ran), calling [onReady] once its result
     * is available -- inline and immediately if it already is, otherwise from the pool thread that
     * finishes it. Never runs discovery for the same file twice concurrently: a second caller made
     * while the first is still in flight joins the same underlying computation.
     *
     * [onReady] is skipped if the future ends up completing exceptionally (see [futureFor]) --
     * there is nothing new to show in that case, and the project closing is the only realistic way
     * that happens (see [dispose]), by which point nothing should be asking this tree to refresh
     * anyway.
     */
    fun ensureDiscovered(file: VirtualFile, onReady: () -> Unit) {
        val future = futureFor(file)
        if (future.isDone) onReady() else future.thenRun(onReady)
    }

    /**
     * Blocks the calling thread until discovery for [file] finishes, reusing an in-flight run
     * rather than starting a second one -- used by [TaskfileGroupNode.getChildren], since expanding
     * a group is a one-off, explicit user action that can afford to wait on its own single result
     * (typically well under a second) rather than needing the same fire-and-forget treatment as
     * populating the whole list.
     */
    fun awaitResult(file: VirtualFile): DiscoveryResult = futureFor(file).resultOrFallback()

    /**
     * Never re-throws: a future can complete exceptionally if the pool task itself threw (see
     * [futureFor]) or if [dispose] cancelled it while still queued (project closing, most likely).
     * Either way the caller just wants *a* result to render, not a crash of the tree's own
     * computation -- [CliFailureReason.OTHER] reuses the same "something went wrong" message an
     * ordinary CLI failure would show.
     */
    private fun CompletableFuture<DiscoveryResult>.resultOrFallback(): DiscoveryResult =
        try {
            join()
        } catch (e: CancellationException) {
            DiscoveryResult(emptyList(), CliFailureReason.OTHER)
        } catch (e: CompletionException) {
            DiscoveryResult(emptyList(), CliFailureReason.OTHER)
        }

    private fun futureFor(file: VirtualFile): CompletableFuture<DiscoveryResult> =
        results.computeIfAbsent(file.path) {
            val future = CompletableFuture<DiscoveryResult>()
            try {
                executor.execute {
                    try {
                        val startedAt = generation.get()
                        val result = TaskDiscovery.discoverDetailed(project, file)
                        rememberSources(file.path, result)
                        // Checked after recording, not just before: an invalidation landing between
                        // the two would otherwise find no edge to follow and leave this result --
                        // computed from what the file used to say -- cached indefinitely. Callers
                        // already waiting still get it, since it is the best answer available right
                        // now, but it is dropped so the next caller recomputes.
                        if (generation.get() != startedAt) {
                            results.remove(file.path, future)
                        }
                        future.complete(result)
                    } catch (e: ProcessCanceledException) {
                        future.completeExceptionally(e)
                        throw e
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }
            } catch (e: RejectedExecutionException) {
                // The executor is already shut down (see dispose()) -- most likely the project is
                // closing and nothing should be calling in anymore, but complete the future anyway
                // rather than letting this exception propagate out of a fresh, un-cached call.
                future.completeExceptionally(e)
            }
            future
        }

    /**
     * Seeds [path]'s entry directly. Only for tests that need a specific result on a row without
     * running discovery to get it -- production code always goes through [futureFor], which is what
     * keeps the include graph recorded.
     */
    @TestOnly
    fun put(path: String, result: DiscoveryResult) {
        results[path] = CompletableFuture.completedFuture(result)
        rememberSources(path, result)
    }

    /**
     * Drops [path]'s own cached result and every result that was built from it -- a Taskfile that
     * `includes:` the edited one has an entry of its own, and that entry is just as stale.
     *
     * One level of edges is enough: a root's [DiscoveryResult.sourceFiles] already lists every file
     * that contributed to it at any depth, so no chain has to be walked here.
     */
    fun invalidate(path: String) {
        generation.incrementAndGet()
        // [path] itself is in the set, not just the roots reached through it: it may be a root of
        // its own (every Taskfile is), and its edges are just as stale as theirs.
        val staleRoots = includedBy.remove(path).orEmpty() + path
        staleRoots.forEach { root ->
            results.remove(root)
            // The root's edges describe a result that no longer exists. Dropping them keeps
            // includedBy from growing without bound as roots come and go, and stops a file the new
            // result no longer includes from evicting it; a re-run records the edges it really has.
            includedBy.values.forEach { it.remove(root) }
        }
    }

    private fun rememberSources(rootPath: String, result: DiscoveryResult) {
        result.sourceFiles
            .filter { it != rootPath }
            .forEach { source ->
                includedBy.computeIfAbsent(source) { ConcurrentHashMap.newKeySet() }.add(rootPath)
            }
    }

    // shutdownNow() drops any not-yet-started computations without completing their futures --
    // left as is, join()/resultOrFallback() would hang on them forever, so every future still
    // outstanding at that point is completed here instead (a no-op for ones already done).
    override fun dispose() {
        executor.shutdownNow()
        results.values.forEach {
            it.completeExceptionally(CancellationException("Project closing"))
        }
        includedBy.clear()
    }

    companion object {
        private const val CONCURRENCY = 6

        fun getInstance(project: Project): TaskDiscoveryCache = project.service()
    }
}
