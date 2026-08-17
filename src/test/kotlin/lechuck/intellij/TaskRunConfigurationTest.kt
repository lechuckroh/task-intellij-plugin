package lechuck.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import lechuck.intellij.vars.VariablesData
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TaskRunConfigurationTest : BasePlatformTestCase() {

    private fun createConfiguration(): TaskRunConfiguration {
        val type = TaskRunConfigurationType()
        val factory = type.configurationFactories[0] as TaskConfigurationFactory
        return TaskRunConfiguration(project, factory, "test")
    }

    @Test
    fun testVariablesArePassedWithoutQuotes() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.variables = VariablesData.create(linkedMapOf("FOO" to "hello world", "BAR" to "x"))

        val params = cfg.buildCommandLine().parametersList.list

        assertTrue(
            "FOO=hello world should be a single argv element",
            params.contains("FOO=hello world"),
        )
        assertTrue("BAR=x should be a single argv element", params.contains("BAR=x"))
        assertFalse(
            "no variable argument should contain literal quotes: $params",
            params.any { it.startsWith("FOO=") && it.contains('"') },
        )
    }

    @Test
    fun testEmptyVariableValue() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.variables = VariablesData.create(linkedMapOf("EMPTY" to ""))

        val params = cfg.buildCommandLine().parametersList.list

        assertTrue("EMPTY= should be passed as-is", params.contains("EMPTY="))
    }

    @Test
    fun testCommandLineShape() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.variables = VariablesData.create(linkedMapOf("FOO" to "bar"))
        cfg.arguments = "--verbose"

        val cmd = cfg.buildCommandLine()
        val params = cmd.parametersList.list

        assertEquals(listOf("build", "FOO=bar", "--", "--verbose"), params)
        assertEquals("task", cmd.exePath)
    }

    // --- working directory ---

    /**
     * With nothing to derive a directory from, task would otherwise inherit the IDE's own working
     * directory and search upwards from there, picking up an unrelated Taskfile.
     */
    @Test
    fun testWorkDirectoryFallsBackToProjectRoot() {
        val cfg = createConfiguration()
        cfg.task = "build"

        assertEquals(project.basePath, cfg.buildCommandLine().workDirectory?.path)
    }

    /**
     * A relative taskfile yields no absolute directory, so it must fall back as well. `sub/T.yml`
     * is the case that matters: its parent `sub` is not null, but it would be resolved against the
     * IDE's working directory rather than the project.
     */
    @Test
    fun testRelativeTaskfileFallsBackToProjectRoot() {
        val cfg = createConfiguration()
        cfg.task = "build"

        for (relative in listOf("Taskfile.yml", "sub/Taskfile.yml", "./Taskfile.yml")) {
            cfg.filename = relative
            assertEquals(relative, project.basePath, cfg.buildCommandLine().workDirectory?.path)
        }
    }

    @Test
    fun testWorkDirectoryComesFromTaskfileParent() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.filename = File(project.basePath, "some-project/Taskfile.yml").path

        assertEquals(
            File(project.basePath, "some-project").path,
            cfg.buildCommandLine().workDirectory?.path,
        )
    }

    /** The directory is derived from the expanded path, not the literal macro. */
    @Test
    fun testPathMacroIsExpandedBeforeDerivingDirectory() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.filename = "\$PROJECT_DIR\$/nested/Taskfile.yml"

        assertEquals(
            File(project.basePath, "nested").path,
            cfg.buildCommandLine().workDirectory?.path,
        )
    }

    @Test
    fun testExplicitWorkingDirectoryWins() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.filename = File(project.basePath, "some-project/Taskfile.yml").path
        cfg.workingDirectory = File(project.basePath, "elsewhere").path

        assertEquals(
            File(project.basePath, "elsewhere").path,
            cfg.buildCommandLine().workDirectory?.path,
        )
    }
}
