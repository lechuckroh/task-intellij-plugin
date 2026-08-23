package lechuck.intellij.discovery

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the real `task` binary rather than a fake, since the whole point of this class is
 * resolving `includes:` the way task itself does. Skipped (not failed) wherever `task` isn't
 * installed, e.g. CI, which doesn't have it.
 */
class TaskCliDiscoveryTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        assumeTrue("requires the task CLI to be installed", isTaskAvailable())
        root = Files.createTempDirectory("task-cli-discovery-test").toFile()
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun successTasks(outcome: CliOutcome): List<DiscoveredTask> {
        assertTrue("expected a Success, got $outcome", outcome is CliOutcome.Success)
        return (outcome as CliOutcome.Success).tasks
    }

    private fun failureReason(outcome: CliOutcome): CliFailureReason {
        assertTrue("expected a Failure, got $outcome", outcome is CliOutcome.Failure)
        return (outcome as CliOutcome.Failure).reason
    }

    @Test
    fun testIncludedTasksAreDiscoveredWithTheirOwnNamespaceAndLocation() {
        val docsTaskfile =
            File(root, "docs/Taskfile.yml").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    version: '3'
                    tasks:
                      build:
                        desc: Build the docs
                        cmds:
                          - echo docs build
                    """
                        .trimIndent()
                )
            }
        val taskfile =
            File(root, "Taskfile.yml").apply {
                writeText(
                    """
                    version: '3'
                    includes:
                      docs: ./docs/Taskfile.yml
                    tasks:
                      build:
                        desc: Build the project
                        cmds:
                          - echo build
                    """
                        .trimIndent()
                )
            }

        val tasks = successTasks(TaskCliDiscovery.discover(taskfile.path))

        assertEquals(setOf("build", "docs:build"), tasks.map { it.name }.toSet())
        val docsBuild = tasks.single { it.name == "docs:build" }
        assertEquals("Build the docs", docsBuild.desc)
        assertEquals(docsTaskfile.path, docsBuild.taskfilePath)
    }

    @Test
    fun testInternalTasksAreExcluded() {
        val taskfile =
            File(root, "Taskfile.yml").apply {
                writeText(
                    """
                    version: '3'
                    tasks:
                      build: echo build
                      hidden:
                        internal: true
                        cmds:
                          - echo hidden
                    """
                        .trimIndent()
                )
            }

        assertEquals(
            listOf("build"),
            successTasks(TaskCliDiscovery.discover(taskfile.path)).map { it.name },
        )
    }

    /**
     * `task --list-all` exits 1 (not 0) when a Taskfile genuinely has zero tasks --
     * indistinguishable from a real failure by exit code alone -- so this must still come back a
     * [CliOutcome.Success] with an empty list, not a [CliOutcome.Failure].
     */
    @Test
    fun testTaskfileWithNoTasksIsStillASuccess() {
        val taskfile = File(root, "Taskfile.yml").apply { writeText("version: '3'\ntasks: {}\n") }

        assertEquals(
            emptyList<DiscoveredTask>(),
            successTasks(TaskCliDiscovery.discover(taskfile.path)),
        )
    }

    /**
     * Exit 100 is a real answer from a working `task`, not a launch failure, but
     * [TaskDiscovery.discover] falls back to [TaskYamlDiscovery] regardless, which finds nothing
     * here either, but a working `task` reporting "no such file" is no different from one this
     * project's fixtures trip up in other ways (e.g. a Taskfile missing `version:`). Distinguishing
     * this from other failures is [CliFailureReason.NO_TASKFILE]'s job, see
     * [lechuck.intellij.explorer.TaskfileGroupNode].
     */
    @Test
    fun testMissingTaskfileReportsNoTaskfile() {
        assertEquals(
            CliFailureReason.NO_TASKFILE,
            failureReason(TaskCliDiscovery.discover(File(root, "no-such-file.yml").path)),
        )
    }

    @Test
    fun testUnparsableTaskfileReportsParseFailed() {
        val taskfile =
            File(root, "Taskfile.yml").apply {
                writeText("version: '3'\ntasks:\n  build: [unclosed\n")
            }

        assertEquals(
            CliFailureReason.PARSE_FAILED,
            failureReason(TaskCliDiscovery.discover(taskfile.path)),
        )
    }

    /**
     * A non-empty [TaskCliDiscovery.discover] `taskExecutable` must actually be the binary that
     * runs, not merely accepted and ignored in favor of the ambient PATH's `task` -- so this points
     * it at a bogus path while a real `task` is still on PATH (guaranteed by this class's own
     * [setUp]) and expects a launch failure, not a silent success.
     */
    @Test
    fun testCustomTaskExecutableIsUsedRatherThanTheAmbientPathsTask() {
        val taskfile =
            File(root, "Taskfile.yml").apply {
                writeText("version: '3'\ntasks:\n  build: echo build\n")
            }

        assertEquals(
            CliFailureReason.CLI_UNAVAILABLE,
            failureReason(
                TaskCliDiscovery.discover(
                    taskfile.path,
                    taskExecutable = File(root, "no-such-task-binary").path,
                )
            ),
        )
    }

    private fun isTaskAvailable(): Boolean =
        try {
            ProcessBuilder("task", "--version").start().waitFor() == 0
        } catch (e: Exception) {
            false
        }
}
