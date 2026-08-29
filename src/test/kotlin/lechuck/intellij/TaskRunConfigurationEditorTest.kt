package lechuck.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TaskRunConfigurationEditorTest : BasePlatformTestCase() {

    private val editor by lazy { TaskRunConfigurationEditor(project) }
    private val createdFiles = mutableListOf<File>()

    // BasePlatformTestCase reuses one light project across the methods of this class, so a Taskfile
    // written by one test would otherwise still be on disk for the next one.
    override fun tearDown() {
        try {
            createdFiles.forEach { it.delete() }
        } catch (e: Throwable) {
            // never let cleanup hide the real failure
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /**
     * Writes a real Taskfile at [relativePath] under the light project's root, which is what
     * `$PROJECT_DIR$` expands to, and makes it visible to the VFS. It has to be a real file on a
     * real filesystem: the code under test looks it up through [LocalFileSystem].
     */
    private fun writeTaskfile(relativePath: String, text: String): File {
        val file = File(project.basePath!!, relativePath)
        file.parentFile.mkdirs()
        file.writeText(text)
        createdFiles.add(file)
        assertNotNull(
            "the freshly written $relativePath must be visible to the VFS",
            LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path),
        )
        return file
    }

    /**
     * `$PROJECT_DIR$/Taskfile.yml` is a valid entry that the run path expands, so the completion
     * lookup has to expand it too. Looking up the literal text finds nothing and used to empty the
     * completion list with no visible error.
     */
    @Test
    fun testPathMacroIsExpandedWhenLocatingTheTaskfile() {
        val file = writeTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  build: echo build\n")

        val byAbsolutePath = editor.resolveTaskfile(file.path)
        val byMacro = editor.resolveTaskfile("\$PROJECT_DIR\$/Taskfile.yml")

        assertNotNull("an absolute path should resolve", byAbsolutePath)
        assertEquals("\$PROJECT_DIR\$ should resolve to the same file", byAbsolutePath, byMacro)
    }

    @Test
    fun testUnknownPathResolvesToNothing() {
        assertNull(
            "a macro pointing at nothing names no file",
            editor.resolveTaskfile("\$PROJECT_DIR\$/no-such-Taskfile.yml"),
        )
    }

    /**
     * findTasks discovers through [lechuck.intellij.discovery.TaskDiscovery], which reads the
     * Taskfile from disk when the `task` CLI is available -- the only way to also resolve
     * `includes:` the way running the task would -- so an edit made in the editor but not saved yet
     * does not show up in that case. Skipped where `task` isn't installed, since
     * [lechuck.intellij.discovery.TaskYamlDiscoveryPsiTest] covers the PSI fallback that applies
     * there instead, and this assertion would not hold for it.
     */
    @Test
    fun testTasksComeFromDiskRatherThanTheEditorTextWhenCliIsAvailable() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val file = writeTaskfile("Taskfile.yml", "version: '3'\ntasks:\n  saved: echo saved\n")
        val virtualFile = editor.resolveTaskfile(file.path)!!

        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("version: '3'\ntasks:\n  saved: echo saved\n  unsaved: echo unsaved\n")
        }

        assertTrue(
            "precondition: the edit must not have reached disk",
            FileDocumentManager.getInstance().isFileModified(virtualFile),
        )
        assertEquals(listOf("saved"), editor.findTasks(virtualFile).toList())
    }

    /**
     * `findTasks`'s `taskExecutable` has to actually reach
     * [lechuck.intellij.discovery.TaskDiscovery] and from there
     * [lechuck.intellij.discovery.TaskCliDiscovery] -- not be silently dropped in favor of the
     * ambient PATH's `task`. Points it at a bogus path while a real `task` is still on PATH
     * (guaranteed by [isTaskAvailable]), and looks for a task that exists *only* in the unsaved
     * editor text: the CLI reads disk and could never report it, so seeing it proves the bogus
     * executable really was used and discovery fell back to the PSI-based parser.
     *
     * (The inverse of [testTasksComeFromDiskRatherThanTheEditorTextWhenCliIsAvailable], which pins
     * the same distinction from the other side.)
     *
     * Written under a test-specific subdirectory, not the shared light-project root other tests in
     * this class use, since [BasePlatformTestCase] reuses one light project (and its Document
     * cache) across every method -- a shared path would risk reading another test's stale cached
     * text.
     */
    @Test
    fun testFindTasksUsesTheGivenTaskExecutableRatherThanTheAmbientPathsTask() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        val file =
            writeTaskfile(
                "${getName()}/Taskfile.yml",
                "version: '3'\ntasks:\n  build: echo build\n",
            )
        val virtualFile = editor.resolveTaskfile(file.path)!!

        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("version: '3'\ntasks:\n  build: echo build\n  unsaved: echo unsaved\n")
        }

        val tasks = editor.findTasks(virtualFile, taskExecutable = "/no/such/task-binary")

        assertEquals(setOf("build", "unsaved"), tasks.toSet())
    }

    private fun isTaskAvailable(): Boolean =
        try {
            ProcessBuilder("task", "--version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
}
