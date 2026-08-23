package lechuck.intellij.discovery

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Covers [TaskYamlDiscovery.discover]'s PSI-based overload directly, independent of whether the
 * `task` CLI is installed on the machine running this test -- unlike [TaskCliDiscoveryTest], which
 * skips itself when it isn't.
 */
@RunWith(JUnit4::class)
class TaskYamlDiscoveryPsiTest : BasePlatformTestCase() {
    private val createdFiles = mutableListOf<File>()

    override fun tearDown() {
        try {
            createdFiles.forEach { it.delete() }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /**
     * There is no `includes:`-correctness to trade away here -- this overload only ever runs once
     * the CLI is already out of the picture -- so an edit not yet saved to disk still has to show
     * up.
     */
    @Test
    fun testUnsavedEditsAreDiscovered() {
        val root = File(project.basePath!!)
        root.mkdirs()
        val file = File(root, "Taskfile.yml")
        file.writeText("tasks:\n  saved: echo saved\n")
        createdFiles.add(file)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)!!

        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("tasks:\n  saved: echo saved\n  unsaved: echo unsaved\n")
        }
        // discover reads the PSI, which follows the document only as of the last commit. The IDE
        // commits between keystrokes on its own; a test has to ask.
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue(
            "precondition: the edit must not have reached disk",
            FileDocumentManager.getInstance().isFileModified(virtualFile),
        )
        assertEquals(
            listOf("saved", "unsaved"),
            TaskYamlDiscovery.discover(project, virtualFile).map { it.name },
        )
    }

    /**
     * [TaskYamlDiscovery.discoverInternalOnly]'s file overload has one piece of behavior the plain
     * string overload (already covered by [TaskYamlDiscoveryTest]) doesn't: filling in
     * [DiscoveredTask.taskfilePath] with [virtualFile]'s own path, since the CLI never reports one
     * for a task it never sees at all.
     *
     * Written under its own subdirectory rather than the shared light-project root
     * [testUnsavedEditsAreDiscovered] uses: both write a literally-named "Taskfile.yml", and
     * BasePlatformTestCase reuses one light project (and its Document cache) across every method in
     * this class, so a shared path would risk reading the other test's stale cached content.
     */
    @Test
    fun testDiscoverInternalOnlySetsTheTaskfilePath() {
        val root = File(project.basePath!!, "${javaClass.simpleName}-${getName()}")
        root.mkdirs()
        val file = File(root, "Taskfile.yml")
        file.writeText("tasks:\n  hidden:\n    internal: true\n    cmds:\n      - echo hidden\n")
        createdFiles.add(file)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path)!!

        val internalTasks = TaskYamlDiscovery.discoverInternalOnly(project, virtualFile)

        assertEquals(listOf("hidden"), internalTasks.map { it.name })
        assertEquals(virtualFile.path, internalTasks.single().taskfilePath)
    }
}
