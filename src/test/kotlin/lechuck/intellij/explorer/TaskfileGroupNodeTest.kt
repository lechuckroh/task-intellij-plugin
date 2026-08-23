package lechuck.intellij.explorer

import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Writes each fixture Taskfile into its own subdirectory of the (shared, cross-test-class) light
 * project root, named for the running test, rather than directly at the root: other test classes
 * (e.g. [lechuck.intellij.TaskLineMarkerProviderTest]) use the literal name "Taskfile.yml" there
 * too, and this class actually shells out to `task`, which reads whatever is on disk regardless of
 * VFS/PSI state -- a stale same-named file left over from another class would be read as-is.
 */
@RunWith(JUnit4::class)
class TaskfileGroupNodeTest : BasePlatformTestCase() {
    private lateinit var testDir: File

    override fun setUp() {
        super.setUp()
        testDir = File(project.basePath!!, "${javaClass.simpleName}-${getName()}")
        testDir.mkdirs()
        // BasePlatformTestCase reuses one light project (and so one TaskExplorerViewSettings)
        // across every method in this class -- reset so an earlier test's toggle can't leak in.
        TaskExplorerViewSettings.getInstance(project).showInternalTasks = false
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

    private fun writeTaskfile(text: String): VirtualFile {
        val file = File(testDir, "Taskfile.yml")
        file.writeText(text)
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)!!
    }

    @Test
    fun testChildrenAreTheDiscoveredTasksInOrder() {
        val virtualFile =
            writeTaskfile("version: '3'\ntasks:\n  build: echo build\n  test: echo test\n")

        val children = TaskfileGroupNode(project, virtualFile) {}.children

        assertEquals(listOf("build", "test"), children.map { (it as TaskNode).name })
    }

    @Test
    fun testEmptyTaskfileHasNoChildren() {
        val virtualFile = writeTaskfile("version: '3'\ntasks: {}\n")

        assertEquals(
            emptyList<TaskNode>(),
            TaskfileGroupNode(project, virtualFile) {}.children.toList(),
        )
    }

    /**
     * The very first [update] call on a freshly-discovered file never blocks: it kicks discovery
     * off on [TaskDiscoveryCache]'s background pool and returns immediately, whether or not that
     * file will end up reporting a warning -- a project with hundreds of Taskfiles renders every
     * row's plain label right away instead of waiting on all of them serially. Proven here with the
     * one fixture that WOULD show a warning once ready, so a regression back to computing inline
     * would fail this by showing the warning too early instead of not blocking at all.
     */
    @Test
    fun testUpdateDoesNotBlockOnUncachedDiscovery() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile = writeTaskfile("version: '3'\ntasks:\n  build: [unclosed\n")
        val node = TaskfileGroupNode(project, virtualFile) {}

        val presentation = PresentationData()
        node.update(presentation)

        assertNull(presentation.locationString)
    }

    /**
     * A Taskfile with genuinely zero tasks must not show a warning -- only a CLI failure (see
     * [testUnparsableTaskfileShowsWhyItHasNoTasksOnceDiscovered]) should. Discovery is forced to
     * finish first (simulating the background pool having gotten to it, see
     * [testUpdateDoesNotBlockOnUncachedDiscovery]), then [update] reads straight from the now-warm
     * cache.
     */
    @Test
    fun testEmptyTaskfileShowsNoWarningOnceDiscovered() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile = writeTaskfile("version: '3'\ntasks: {}\n")
        TaskDiscoveryCache.getInstance(project).awaitResult(virtualFile)
        val node = TaskfileGroupNode(project, virtualFile) {}

        val presentation = PresentationData()
        node.update(presentation)

        assertNull(presentation.locationString)
    }

    @Test
    fun testUnparsableTaskfileShowsWhyItHasNoTasksOnceDiscovered() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile = writeTaskfile("version: '3'\ntasks:\n  build: [unclosed\n")
        TaskDiscoveryCache.getInstance(project).awaitResult(virtualFile)
        val node = TaskfileGroupNode(project, virtualFile) {}

        val presentation = PresentationData()
        node.update(presentation)

        assertEquals("Failed to parse Taskfile", presentation.locationString)
    }

    @Test
    fun testInternalTasksAreHiddenByDefault() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile =
            writeTaskfile(
                "version: '3'\ntasks:\n  build: echo build\n  hidden:\n    internal: true\n    cmds:\n      - echo hidden\n"
            )

        val children = TaskfileGroupNode(project, virtualFile) {}.children

        assertEquals(listOf("build"), children.map { (it as TaskNode).name })
    }

    @Test
    fun testInternalTasksAppearWhenTheToggleIsOn() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val virtualFile =
            writeTaskfile(
                "version: '3'\ntasks:\n  build: echo build\n  hidden:\n    internal: true\n    cmds:\n      - echo hidden\n"
            )
        TaskExplorerViewSettings.getInstance(project).showInternalTasks = true

        val children = TaskfileGroupNode(project, virtualFile) {}.children

        assertEquals(setOf("build", "hidden"), children.map { (it as TaskNode).name }.toSet())
    }

    private fun isTaskAvailable(): Boolean =
        try {
            ProcessBuilder("task", "--version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
}
