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
import lechuck.intellij.discovery.CliFailureReason
import lechuck.intellij.discovery.DiscoveryResult
import lechuck.intellij.discovery.TaskDiscovery

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
                            if (
                                TaskfileFinder.isRecognizedName(event.path.substringAfterLast('/'))
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
                        future.complete(TaskDiscovery.discoverDetailed(project, file))
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

    fun invalidate(path: String) {
        results.remove(path)
    }

    // shutdownNow() drops any not-yet-started computations without completing their futures --
    // left as is, join()/resultOrFallback() would hang on them forever, so every future still
    // outstanding at that point is completed here instead (a no-op for ones already done).
    override fun dispose() {
        executor.shutdownNow()
        results.values.forEach {
            it.completeExceptionally(CancellationException("Project closing"))
        }
    }

    companion object {
        private const val CONCURRENCY = 6

        fun getInstance(project: Project): TaskDiscoveryCache = project.service()
    }
}
