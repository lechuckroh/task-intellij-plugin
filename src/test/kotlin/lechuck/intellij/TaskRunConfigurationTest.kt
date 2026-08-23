package lechuck.intellij

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import lechuck.intellij.vars.VariablesData
import org.junit.Assert.assertThrows
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

    /**
     * Unlike a taskfile-derived directory (discarded when relative, see
     * testRelativeTaskfileFallsBackToProjectRoot), an explicit workingDirectory has no
     * double-application risk, so a relative value is joined against the project root rather than
     * being resolved against the IDE's own working directory.
     */
    @Test
    fun testRelativeWorkingDirectoryIsJoinedWithProjectRoot() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.workingDirectory = "sub"

        assertEquals(
            File(project.basePath, "sub").path,
            cfg.buildCommandLine().workDirectory?.path,
        )
    }

    @Test
    fun testDotWorkingDirectoryResolvesToProjectRoot() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.workingDirectory = "."

        assertEquals(
            File(project.basePath, ".").path,
            cfg.buildCommandLine().workDirectory?.path,
        )
    }

    /**
     * A whitespace-only value is treated the same as unset, per StringUtil.isEmptyOrSpaces's
     * convention.
     */
    @Test
    fun testBlankWorkingDirectoryFallsBackToProjectRoot() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.workingDirectory = "   "

        assertEquals(project.basePath, cfg.buildCommandLine().workDirectory?.path)
    }

    /** A relative value with leading/trailing whitespace is trimmed before joining. */
    @Test
    fun testWorkingDirectoryIsTrimmedBeforeJoining() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.workingDirectory = "  sub  "

        assertEquals(
            File(project.basePath, "sub").path,
            cfg.buildCommandLine().workDirectory?.path,
        )
    }

    /** Regression: an absolute workingDirectory must not be joined onto the project root. */
    @Test
    fun testAbsoluteWorkingDirectoryIsUnchanged() {
        val cfg = createConfiguration()
        cfg.task = "build"
        val absolute = File(project.basePath, "elsewhere").path
        cfg.workingDirectory = absolute

        assertEquals(absolute, cfg.buildCommandLine().workDirectory?.path)
    }

    /** Regression: the macro must still be expanded, and the expanded path is already absolute. */
    @Test
    fun testMacroWorkingDirectoryIsExpanded() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.workingDirectory = "\$PROJECT_DIR\$"

        assertEquals(project.basePath, cfg.buildCommandLine().workDirectory?.path)
    }

    @Test
    fun testCheckConfigurationRequiresTask() {
        val cfg = createConfiguration()

        assertThrows(RuntimeConfigurationError::class.java) { cfg.checkConfiguration() }
    }

    /** Empty optional fields are not errors: task runs from PATH against the found Taskfile. */
    @Test
    fun testCheckConfigurationAcceptsMinimalConfiguration() {
        val cfg = createConfiguration()
        cfg.task = "build"

        cfg.checkConfiguration()
    }

    /** Missing files warn instead of erroring, so the run button stays enabled. */
    @Test
    fun testCheckConfigurationWarnsOnMissingTaskfile() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.filename = "/no/such/dir/Taskfile.yml"

        assertThrows(RuntimeConfigurationWarning::class.java) { cfg.checkConfiguration() }
    }

    /** The check has to expand macros the same way buildCommandLine does. */
    @Test
    fun testCheckConfigurationExpandsMacroInTaskfilePath() {
        val cfg = createConfiguration()
        cfg.task = "build"

        cfg.filename = "\$PROJECT_DIR\$/no-such-Taskfile.yml"
        assertThrows(RuntimeConfigurationWarning::class.java) { cfg.checkConfiguration() }

        val file = File(project.basePath!!, "Taskfile.yml")
        file.parentFile.mkdirs()
        file.writeText("tasks:\n  build: echo ok\n")
        try {
            cfg.filename = "\$PROJECT_DIR\$/Taskfile.yml"
            cfg.checkConfiguration()
        } finally {
            file.delete()
        }
    }

    /**
     * A bare name is looked up through PATH and a relative path resolves against a base this check
     * cannot know, so neither may draw a warning. buildCommandLine passes taskPath to the OS
     * without macro expansion, so the check reads it as written too.
     */
    @Test
    fun testCheckConfigurationSkipsNonAbsoluteExecutable() {
        val cfg = createConfiguration()
        cfg.task = "build"

        cfg.taskPath = "task"
        cfg.checkConfiguration()

        cfg.taskPath = "bin/task"
        cfg.checkConfiguration()
    }

    @Test
    fun testCheckConfigurationWarnsOnMissingAbsoluteExecutable() {
        val cfg = createConfiguration()
        cfg.task = "build"
        cfg.taskPath = "/no/such/bin/task"

        assertThrows(RuntimeConfigurationWarning::class.java) { cfg.checkConfiguration() }
    }

    @Test
    fun testCheckConfigurationWarnsOnMissingWorkingDirectory() {
        val cfg = createConfiguration()
        cfg.task = "build"

        cfg.workingDirectory = "/no/such/dir"
        assertThrows(RuntimeConfigurationWarning::class.java) { cfg.checkConfiguration() }

        cfg.workingDirectory = System.getProperty("java.io.tmpdir")
        cfg.checkConfiguration()
    }
}
