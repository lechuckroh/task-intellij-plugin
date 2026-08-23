package lechuck.intellij.explorer

import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TaskfileFinderTest : BasePlatformTestCase() {
    private val prefix
        get() = "${javaClass.simpleName}-${getName()}"

    /**
     * [com.intellij.testFramework.fixtures.CodeInsightTestFixture.addFileToProject] puts the file
     * under the light project's module's actual content/source root (its fixture-managed temp VFS
     * root). A plain java.io.File write under [com.intellij.openapi.project.Project.getBasePath]
     * lands outside every content root instead -- no amount of VFS refreshing changes that -- so
     * [com.intellij.psi.search.FilenameIndex], which is scoped to project content, would never find
     * it, unlike the plain VFS/PSI lookups elsewhere in this codebase's tests, which only need the
     * file to exist somewhere on disk.
     */
    private fun addTaskfile(relativePath: String, text: String) {
        myFixture.addFileToProject("$prefix/$relativePath", text)
    }

    @Test
    fun testFindsEveryRecognizedNameVariantButNothingElse() {
        addTaskfile("a/Taskfile.yml", "version: '3'\ntasks: {}\n")
        addTaskfile("b/taskfile.yaml", "version: '3'\ntasks: {}\n")
        addTaskfile("c/Taskfile.dist.yml", "version: '3'\ntasks: {}\n")
        addTaskfile("d/not-a-taskfile.yml", "version: '3'\ntasks: {}\n")
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val found = TaskfileFinder.findAll(project).map { it.name }.toSet()

        assertEquals(setOf("Taskfile.yml", "taskfile.yaml", "Taskfile.dist.yml"), found)
    }

    @Test
    fun testIsRecognizedNameMatchesTheSameNamesFindAllDoes() {
        for (name in
            listOf(
                "Taskfile.yml",
                "taskfile.yml",
                "Taskfile.yaml",
                "taskfile.yaml",
                "Taskfile.dist.yml",
                "taskfile.dist.yml",
                "Taskfile.dist.yaml",
                "taskfile.dist.yaml",
            )) {
            assertTrue("$name should be recognized", TaskfileFinder.isRecognizedName(name))
        }

        assertFalse(TaskfileFinder.isRecognizedName("TASKFILE.YML"))
        assertFalse(TaskfileFinder.isRecognizedName("not-a-taskfile.yml"))
        assertFalse(TaskfileFinder.isRecognizedName("Taskfile.yml.bak"))
    }
}
