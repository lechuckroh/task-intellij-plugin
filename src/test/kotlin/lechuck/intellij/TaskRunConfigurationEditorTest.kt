package lechuck.intellij

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
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
     * Writes a real Taskfile at the root of the light project, which is what `$PROJECT_DIR$`
     * expands to, and makes it visible to the VFS. It has to be a real file on a real filesystem:
     * the code under test looks it up through [LocalFileSystem].
     */
    private fun writeTaskfile(name: String, text: String): File {
        val root = File(project.basePath!!)
        root.mkdirs()
        val file = File(root, name)
        file.writeText(text)
        createdFiles.add(file)
        assertNotNull(
            "the freshly written $name must be visible to the VFS",
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
        val file = writeTaskfile("Taskfile.yml", "tasks:\n  build: echo build\n")

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
     * A task typed into the editor but not saved yet has to appear in the completion list. Reading
     * the file's bytes would return the version on disk and miss it.
     */
    @Test
    fun testTasksComeFromTheEditorTextRatherThanDisk() {
        val file = writeTaskfile("Taskfile.yml", "tasks:\n  saved: echo saved\n")
        val virtualFile = editor.resolveTaskfile(file.path)!!
        // the PSI exists before the edit, as it does while the file is open in the IDE
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)!!

        val document = FileDocumentManager.getInstance().getDocument(virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("tasks:\n  saved: echo saved\n  unsaved: echo unsaved\n")
        }
        // findTasks reads the PSI, which follows the document only as of the last commit. The IDE
        // commits between keystrokes on its own; a test has to ask, and the assertion below rests
        // on it having happened.
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue(
            "precondition: the edit must not have reached disk",
            FileDocumentManager.getInstance().isFileModified(virtualFile),
        )
        assertEquals(listOf("saved", "unsaved"), editor.findTasks(psiFile).toList())
    }
}
