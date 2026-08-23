package lechuck.intellij.explorer

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
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
