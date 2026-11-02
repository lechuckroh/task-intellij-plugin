package lechuck.intellij.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The guard, which is the part of WSL support that has to hold on every machine. The patching
 * itself needs a real distribution and so cannot be exercised here; it is deliberately the thinnest
 * wrapper the API allows, with every decision in [WslRunPlanner], which [WslRunPlannerTest] covers
 * in full.
 */
@RunWith(JUnit4::class)
class TaskWslSupportTest : BasePlatformTestCase() {

    /** An ordinary run must not be touched, whatever the host is. */
    @Test
    fun testOrdinaryCommandLineIsNotPatched() {
        val cmdLine =
            GeneralCommandLine("task", "--list-all", "--json", "--no-status", "-t", "/tmp/T.yml")

        assertNull(TaskWslSupport.patchForWsl(project, cmdLine))
    }

    /**
     * Off Windows there is no WSL to run anything in, so even a path that looks like one is left
     * alone -- the reason Linux and macOS behaviour is unchanged by construction rather than by
     * accident.
     */
    @Test
    fun testNothingIsPatchedOffWindows() {
        assumeTrue("Windows-only behaviour is asserted separately", !SystemInfo.isWindows)
        val cmdLine =
            GeneralCommandLine("task", "--taskfile", "//wsl.localhost/Ubuntu/home/x/Taskfile.yml")

        assertNull(TaskWslSupport.patchForWsl(project, cmdLine))
    }

    /**
     * [WslPaths] re-implements the platform's parser rather than calling it (see its docs for why),
     * so the two are pinned together here. Only meaningful on Windows, where the platform's parser
     * answers at all.
     */
    @Test
    fun testParserAgreesWithThePlatformOnWindows() {
        assumeTrue("the platform's parser only answers on Windows", SystemInfo.isWindows)

        for (path in
            listOf(
                """\\wsl.localhost\Ubuntu\home\x\Taskfile.yml""",
                "//wsl.localhost/Ubuntu/home/x/Taskfile.yml",
                """\\wsl$\Ubuntu\home\x""",
                """\\wsl.localhost\Ubuntu""",
                """C:\projects\x\Taskfile.yml""",
                "/home/x/Taskfile.yml",
            )) {
            val platform = WslPath.parseWindowsUncPath(path)
            val ours = WslPaths.parse(path)

            assertEquals(path, platform?.distributionId, ours?.distributionId)
            assertEquals(path, platform?.linuxPath, ours?.linuxPath)
        }
    }
}
