package lechuck.intellij.explorer

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import lechuck.intellij.discovery.CliFailureReason
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TaskDiscoveryCacheTest : BasePlatformTestCase() {
    private lateinit var testDir: File

    override fun setUp() {
        super.setUp()
        testDir = File(project.basePath!!, "${javaClass.simpleName}-${getName()}")
        testDir.mkdirs()
    }

    override fun tearDown() {
        try {
            testDir.deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun writeTaskfile(text: String) =
        File(testDir, "Taskfile.yml")
            .apply { writeText(text) }
            .let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it.path)!! }

    @Test
    fun testAwaitResultCachesSoASecondCallDoesNotRediscover() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile = writeTaskfile("version: '3'\ntasks:\n  build: echo build\n")
        val cache = TaskDiscoveryCache.getInstance(project)

        val first = cache.awaitResult(virtualFile)
        val second = cache.cachedResult(virtualFile)

        assertEquals(listOf("build"), first.tasks.map { it.name })
        assertSame("a second read must reuse the same result, not recompute it", first, second)
    }

    @Test
    fun testEnsureDiscoveredCallsOnReadyOnceTheResultIsAvailable() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile = writeTaskfile("version: '3'\ntasks:\n  build: echo build\n")
        val cache = TaskDiscoveryCache.getInstance(project)
        val latch = CountDownLatch(1)

        cache.ensureDiscovered(virtualFile) { latch.countDown() }

        assertTrue(
            "onReady should fire once discovery finishes",
            latch.await(5, TimeUnit.SECONDS),
        )
        assertNotNull(cache.cachedResult(virtualFile))
    }

    @Test
    fun testInvalidateForcesRediscovery() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile = writeTaskfile("version: '3'\ntasks:\n  build: echo build\n")
        val cache = TaskDiscoveryCache.getInstance(project)
        cache.awaitResult(virtualFile)

        cache.invalidate(virtualFile.path)

        assertNull("a fresh call has nothing cached yet", cache.cachedResult(virtualFile))
        assertEquals(listOf("build"), cache.awaitResult(virtualFile).tasks.map { it.name })
    }

    /**
     * The cache evicts its own entries when a Taskfile changes on disk, without anything else
     * having to notice: this used to be TaskExplorerPanel's job, which meant it only happened in a
     * session where the user had actually opened the tool window -- every other consumer (the Run
     * Anything provider) could read an entry that outlived the file's content indefinitely.
     */
    @Test
    fun testAnEditToTheTaskfileEvictsItsCachedResultWithoutAnyToolWindow() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile = writeTaskfile("version: '3'\ntasks:\n  build: echo build\n")
        val cache = TaskDiscoveryCache.getInstance(project)
        cache.awaitResult(virtualFile)
        assertNotNull(cache.cachedResult(virtualFile))

        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(virtualFile, "version: '3'\ntasks:\n  deploy: echo deploy\n")
        }

        assertNull(
            "the edited Taskfile's stale result must be gone",
            cache.cachedResult(virtualFile),
        )
        assertEquals(listOf("deploy"), cache.awaitResult(virtualFile).tasks.map { it.name })
    }

    /**
     * Editing an *included* Taskfile has to invalidate the entry cached under the file that
     * includes it, not just its own: that entry's task list was built from both files. The reverse
     * edges come from [lechuck.intellij.discovery.DiscoveryResult.sourceFiles], which is why this
     * holds whichever backend answered.
     */
    @Test
    fun testEditingAnIncludedTaskfileInvalidatesTheIncludingOne() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val includedDir = File(testDir, "inc").apply { mkdirs() }
        val included = File(includedDir, "Taskfile.yml")
        included.writeText("version: '3'\ntasks:\n  helper: echo helper\n")
        val root = writeTaskfile("version: '3'\nincludes:\n  inc: ./inc/Taskfile.yml\n")
        LocalFileSystem.getInstance().refreshAndFindFileByPath(included.path)!!
        val cache = TaskDiscoveryCache.getInstance(project)
        assertEquals(listOf("inc:helper"), cache.awaitResult(root).tasks.map { it.name })

        cache.invalidate(included.path)

        assertNull("the including Taskfile's result is stale too", cache.cachedResult(root))
    }

    /**
     * The listener's name filter cannot see an include like `taskfile: ./common.yml`, which is not
     * one of the 8 names a Taskfile is recognized by -- and `includes:` accepts any path at all. So
     * the listener also invalidates a file that is *known to have contributed* to some cached
     * result, whatever it is called. Drives the real VFS event rather than calling `invalidate`,
     * since that branch is the only thing standing between an edited include and a stale tree.
     */
    @Test
    fun testEditingAnArbitrarilyNamedIncludeIsNoticedByTheVfsListener() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val included = File(testDir, "common.yml")
        included.writeText("version: '3'\ntasks:\n  helper: echo helper\n")
        val root = writeTaskfile("version: '3'\nincludes:\n  c: ./common.yml\n")
        val includedFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(included.path)!!
        val cache = TaskDiscoveryCache.getInstance(project)
        assertEquals(listOf("c:helper"), cache.awaitResult(root).tasks.map { it.name })

        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(includedFile, "version: '3'\ntasks:\n  renamed: echo renamed\n")
        }

        assertNull("the including Taskfile's result is stale now", cache.cachedResult(root))
        assertEquals(listOf("c:renamed"), cache.awaitResult(root).tasks.map { it.name })
    }

    /**
     * An intermediate Taskfile that only re-includes others contributes no task of its own, so the
     * CLI -- which reports where each *task* was defined -- never names it. Editing it still
     * changes what its includer offers, which is why the CLI path unions the CLI's own task
     * locations with [lechuck.intellij.discovery.TaskYamlDiscovery.sourceFilesOf]'s walk of the
     * include graph.
     */
    @Test
    fun testEditingAnIntermediateTaskfileWithNoTasksOfItsOwnInvalidatesTheRoot() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val deepDir = File(testDir, "deep").apply { mkdirs() }
        File(deepDir, "Taskfile.yml").writeText("version: '3'\ntasks:\n  deep: echo deep\n")
        val midDir = File(testDir, "mid").apply { mkdirs() }
        val mid = File(midDir, "Taskfile.yml")
        mid.writeText("version: '3'\nincludes:\n  d: ../deep/Taskfile.yml\n")
        val root = writeTaskfile("version: '3'\nincludes:\n  m: ./mid/Taskfile.yml\n")
        val midFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(mid.path)!!
        val cache = TaskDiscoveryCache.getInstance(project)
        assertEquals(listOf("m:d:deep"), cache.awaitResult(root).tasks.map { it.name })

        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(
                midFile,
                "version: '3'\nincludes:\n  d: ../deep/Taskfile.yml\ntasks:\n  own: echo own\n",
            )
        }

        assertNull("the root's list is short by the new task", cache.cachedResult(root))
        assertEquals(
            listOf("m:d:deep", "m:own"),
            cache.awaitResult(root).tasks.map { it.name }.sorted(),
        )
    }

    /**
     * Invalidating a root drops the edges pointing at it, so `includedBy` does not accumulate an
     * entry for every root that ever existed. Observable only through behaviour: after the root is
     * re-discovered from a Taskfile that no longer includes the old file, editing that old file
     * must no longer evict the root.
     */
    @Test
    fun testInvalidatingARootForgetsTheEdgesOfTheResultItReplaced() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val included = File(testDir, "old.yml")
        included.writeText("version: '3'\ntasks:\n  old: echo old\n")
        val root = writeTaskfile("version: '3'\nincludes:\n  o: ./old.yml\n")
        LocalFileSystem.getInstance().refreshAndFindFileByPath(included.path)!!
        val cache = TaskDiscoveryCache.getInstance(project)
        cache.awaitResult(root)

        WriteAction.runAndWait<Throwable> {
            VfsUtil.saveText(root, "version: '3'\ntasks:\n  standalone: echo standalone\n")
        }
        // The parser half of discovery reads PSI, which lags the write until documents are
        // committed; without this it would still see the include that was just deleted.
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(listOf("standalone"), cache.awaitResult(root).tasks.map { it.name })
        cache.invalidate(included.path)

        assertNotNull("the root no longer includes that file", cache.cachedResult(root))
    }

    /**
     * [TaskDiscoveryCache.dispose] shuts down the executor and completes every outstanding future
     * rather than leaving `awaitResult`/`join()` blocked forever on work that will now never run --
     * this is the scenario a project closing with Taskfiles still queued produces. A disposed cache
     * is never reused by the real tree (its own lifecycle ends with the project's), so this test
     * constructs its own throwaway instance instead of the project-wide singleton.
     */
    @Test
    fun testDisposeCompletesOutstandingWorkInsteadOfHangingForever() {
        val virtualFile = writeTaskfile("version: '3'\ntasks:\n  build: echo build\n")
        val cache = TaskDiscoveryCache(project)
        Disposer.dispose(cache)

        val result = cache.awaitResult(virtualFile)

        assertEquals(emptyList<Any>(), result.tasks)
        assertEquals(CliFailureReason.OTHER, result.warning)
    }

    private fun isTaskAvailable(): Boolean =
        try {
            ProcessBuilder("task", "--version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
}
