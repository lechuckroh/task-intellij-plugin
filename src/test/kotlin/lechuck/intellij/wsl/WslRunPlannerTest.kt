package lechuck.intellij.wsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The planner holds every decision WSL support makes, and no platform call, so all of it can be
 * asserted about here rather than only on a Windows machine with a distribution installed.
 */
class WslRunPlannerTest {

    private val taskfile = """\\wsl.localhost\Ubuntu\home\x\Taskfile.yml"""
    private val workDirectory = """\\wsl.localhost\Ubuntu\home\x"""

    /**
     * The no-op guarantee, from the pure side: nothing about a run that touches no distribution is
     * rewritten, which is what keeps Linux, macOS and Windows-local projects on exactly the command
     * line they had before any of this existed.
     */
    @Test
    fun testNothingWslMeansNoPlan() {
        assertNull(
            WslRunPlanner.plan("task", "/home/x", listOf("--taskfile", "/home/x/Taskfile.yml"))
        )
        assertNull(
            WslRunPlanner.plan(
                """C:\tools\task.exe""",
                """C:\projects\x""",
                listOf("--taskfile", """C:\projects\x\Taskfile.yml"""),
            )
        )
        assertNull(WslRunPlanner.plan("task", null, emptyList()))
    }

    @Test
    fun testTaskfileSelectsTheDistributionAndIsMapped() {
        val plan = WslRunPlanner.plan("", workDirectory, listOf("--taskfile", taskfile, "build"))!!

        assertEquals("Ubuntu", plan.distributionId)
        assertEquals("//wsl.localhost/Ubuntu", plan.uncRoot)
        assertEquals("task", plan.exePath)
        assertEquals(listOf("--taskfile", "/home/x/Taskfile.yml", "build"), plan.parameters)
        assertEquals("/home/x", plan.remoteWorkingDirectory)
        assertNull(plan.windowsWorkingDirectory)
        assertNull(plan.ignoredWindowsExecutable)
    }

    /** Discovery's `-t` needs no rule of its own: paths are rewritten by shape, not by flag. */
    @Test
    fun testDiscoveryArgumentsAreMappedTheSameWay() {
        val plan =
            WslRunPlanner.plan(
                "",
                null,
                listOf("--list-all", "--json", "--no-status", "-t", taskfile),
            )!!

        assertEquals(
            listOf("--list-all", "--json", "--no-status", "-t", "/home/x/Taskfile.yml"),
            plan.parameters,
        )
    }

    /** Only paths of the selected distribution change; every other argument is passed through. */
    @Test
    fun testOtherArgumentsArePassedThroughExactly() {
        val plan =
            WslRunPlanner.plan(
                "",
                workDirectory,
                listOf("--taskfile", taskfile, "build", "FOO=hello world", "--", "--verbose"),
            )!!

        assertEquals(
            listOf(
                "--taskfile",
                "/home/x/Taskfile.yml",
                "build",
                "FOO=hello world",
                "--",
                "--verbose",
            ),
            plan.parameters,
        )
    }

    /** A value that merely mentions the root without being a path must not be touched. */
    @Test
    fun testArgumentsThatOnlyMentionTheRootAreUntouched() {
        val plan =
            WslRunPlanner.plan(
                "",
                workDirectory,
                listOf("--taskfile", taskfile, "ROOT=wsl.localhost"),
            )!!

        assertEquals("ROOT=wsl.localhost", plan.parameters.last())
    }

    // --- executable ---

    @Test
    fun testEmptyExecutableResolvesFromTheDistributionPath() {
        val plan = WslRunPlanner.plan("", workDirectory, listOf("--taskfile", taskfile))!!

        assertEquals("task", plan.exePath)
        assertNull(plan.ignoredWindowsExecutable)
    }

    /** A user who typed a Linux path meant it. */
    @Test
    fun testLinuxExecutableIsUsedVerbatim() {
        val plan =
            WslRunPlanner.plan(
                "/usr/local/bin/task",
                workDirectory,
                listOf("--taskfile", taskfile),
            )!!

        assertEquals("/usr/local/bin/task", plan.exePath)
        assertNull(plan.ignoredWindowsExecutable)
    }

    @Test
    fun testUncExecutableIsMapped() {
        val plan =
            WslRunPlanner.plan(
                """\\wsl.localhost\Ubuntu\usr\local\bin\task""",
                workDirectory,
                listOf("--taskfile", taskfile),
            )!!

        assertEquals("/usr/local/bin/task", plan.exePath)
        assertNull(plan.ignoredWindowsExecutable)
    }

    @Test
    fun testWindowsExecutableIsDroppedAndReported() {
        val plan =
            WslRunPlanner.plan(
                """C:\tools\task.exe""",
                workDirectory,
                listOf("--taskfile", taskfile),
            )!!

        assertEquals("task", plan.exePath)
        assertEquals("""C:\tools\task.exe""", plan.ignoredWindowsExecutable)
    }

    /**
     * A binary belonging to a different distribution is as unusable here as a Windows one -- and
     * must never reach the platform's path mapping, which throws across distributions.
     */
    @Test
    fun testExecutableFromAnotherDistributionIsDropped() {
        val plan =
            WslRunPlanner.plan(
                """\\wsl.localhost\Debian\usr\bin\task""",
                workDirectory,
                listOf("--taskfile", taskfile),
            )!!

        assertEquals("task", plan.exePath)
        assertEquals("""\\wsl.localhost\Debian\usr\bin\task""", plan.ignoredWindowsExecutable)
    }

    // --- working directory ---

    @Test
    fun testLinuxWorkingDirectoryIsPassedThrough() {
        val plan = WslRunPlanner.plan("", "/home/x/sub", listOf("--taskfile", taskfile))!!

        assertEquals("/home/x/sub", plan.remoteWorkingDirectory)
        assertNull(plan.windowsWorkingDirectory)
    }

    /**
     * Only the distribution can say where Windows drives are mounted, so this is handed on as-is.
     */
    @Test
    fun testWindowsWorkingDirectoryIsLeftForTheDistribution() {
        val plan = WslRunPlanner.plan("", """C:\tmp""", listOf("--taskfile", taskfile))!!

        assertNull(plan.remoteWorkingDirectory)
        assertEquals("""C:\tmp""", plan.windowsWorkingDirectory)
    }

    /** Nothing usable: the Taskfile's own parent is where task would have run anyway. */
    @Test
    fun testWorkingDirectoryFallsBackToTheTaskfileParent() {
        // Both spellings of another distribution's root: the forward-slash one also starts with a
        // separator, so it would otherwise be mistaken for a path inside this distribution.
        for (other in
            listOf("""\\wsl.localhost\Debian\home\x""", "//wsl.localhost/Debian/home/x")) {
            val plan = WslRunPlanner.plan("", other, listOf("--taskfile", taskfile))!!

            assertEquals(other, "/home/x", plan.remoteWorkingDirectory)
            assertNull(other, plan.windowsWorkingDirectory)
        }

        assertEquals(
            "/home/x",
            WslRunPlanner.plan("", null, listOf("--taskfile", taskfile))!!.remoteWorkingDirectory,
        )
    }

    /** With no WSL argument at all, the working directory is what names the distribution. */
    @Test
    fun testWorkingDirectoryCanSelectTheDistribution() {
        val plan = WslRunPlanner.plan("", workDirectory, listOf("build"))!!

        assertEquals("Ubuntu", plan.distributionId)
        assertEquals("/home/x", plan.remoteWorkingDirectory)
        assertEquals(listOf("build"), plan.parameters)
    }
}
