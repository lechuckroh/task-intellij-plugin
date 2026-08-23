package lechuck.intellij.explorer

import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import lechuck.intellij.TaskRunConfiguration
import lechuck.intellij.discovery.DiscoveredTask
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers [TaskNode.run] itself calling through to [lechuck.intellij.TaskLineMarkerProvider.runTask]
 * with the right arguments -- the same seam [lechuck.intellij.TaskLineMarkerProviderTest] already
 * covers in depth for the gutter's own caller, so this only needs to check the wiring, not re-prove
 * `runTask`'s own behavior.
 */
@RunWith(JUnit4::class)
class TaskNodeTest : BasePlatformTestCase() {
    private lateinit var testDir: File

    override fun setUp() {
        super.setUp()
        testDir = File(project.basePath!!, "${javaClass.simpleName}-${getName()}")
        testDir.mkdirs()
    }

    // BasePlatformTestCase reuses one light project across the methods of this class, so a run
    // configuration created by a test would otherwise leak into the next one.
    override fun tearDown() {
        try {
            val runManager = RunManagerImpl.getInstanceImpl(project)
            runManager.removeConfigurations(runManager.allSettings.toList())
            testDir.deleteRecursively()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /**
     * [run] doesn't just prepare a configuration --
     * [lechuck.intellij.TaskLineMarkerProvider.runTask] actually executes it, which needs a real
     * `task` binary to launch cleanly. Without one, the platform's own execution machinery logs an
     * error before our code ever gets a chance to catch the resulting `ExecutionException`, which
     * fails the test regardless -- unlike every CLI call this suite makes only to *discover* tasks
     * (see [TaskCliDiscovery]'s own deliberately quiet fallback), which doesn't hit that path at
     * all.
     */
    @Test
    fun testRunPreparesAndSelectsTheTasksOwnConfiguration() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val file = File(testDir, "Taskfile.yml")
        file.writeText("version: '3'\ntasks:\n  build: echo build\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)!!

        val node = TaskfileGroupNode(project, virtualFile) {}.children.single() as TaskNode
        node.run()

        val selected = RunManagerImpl.getInstanceImpl(project).selectedConfiguration
        assertNotNull("Should select a configuration after running", selected)
        val runConfig = selected!!.configuration as TaskRunConfiguration
        assertEquals("build", runConfig.task)
        assertEquals(virtualFile.path, runConfig.filename)
    }

    /**
     * Constructs [DiscoveredTask] directly rather than going through real discovery, so [line] is
     * deterministic instead of whatever the installed `task` CLI happens to report.
     */
    @Test
    fun testNavigateOpensTheFileAtTheTasksLine() {
        val file = File(testDir, "Taskfile.yml")
        file.writeText("version: '3'\ntasks:\n  build: echo build\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)!!
        val task =
            DiscoveredTask().also {
                it.name = "build"
                it.taskfilePath = virtualFile.path
                it.line = 3
            }
        val node = TaskNode(project, task, virtualFile.path)

        assertTrue(node.canNavigate())
        node.navigate(true)

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull("navigate should open an editor", editor)
        assertEquals(2, editor!!.caretModel.logicalPosition.line)
    }

    @Test
    fun testCanNavigateIsFalseWhenTheFileNoLongerExists() {
        val task =
            DiscoveredTask().also {
                it.name = "build"
                it.taskfilePath = "/no/such/file/Taskfile.yml"
            }
        val node = TaskNode(project, task, "/no/such/file/Taskfile.yml")

        assertFalse(node.canNavigate())
    }

    /**
     * An internal task found via
     * [lechuck.intellij.discovery.TaskYamlDiscovery.discoverInternalOnly] always has its own
     * [DiscoveredTask.taskfilePath] set, but nothing else guarantees every task does -- falling
     * back to the group's own Taskfile keeps `navigate` working regardless.
     */
    @Test
    fun testNavigateFallsBackToTheGroupsTaskfilePathWhenTheTaskHasNone() {
        val file = File(testDir, "Taskfile.yml")
        file.writeText("version: '3'\ntasks:\n  build: echo build\n")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)!!
        val task = DiscoveredTask().also { it.name = "build" }
        val node = TaskNode(project, task, virtualFile.path)

        assertTrue(node.canNavigate())
    }

    private fun isTaskAvailable(): Boolean =
        try {
            ProcessBuilder("task", "--version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
}
