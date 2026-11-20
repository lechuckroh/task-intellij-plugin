package lechuck.intellij.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path

/**
 * Runs `task` inside the WSL distribution its Taskfile lives in.
 *
 * A project opened from `\\wsl.localhost\Ubuntu\...` is a Linux project that happens to be visible
 * from Windows: its `cmds:` are Linux commands, its `includes:` and `dotenv:` entries are Linux
 * paths, and the `task` that has to interpret them is the distribution's own. Handing those paths
 * to a Windows `task.exe` parses the Taskfile and then fails at everything after it.
 *
 * This is the only file in the plugin that touches the WSL API, and it is deliberately a handful of
 * calls long: the decisions all live in [WslRunPlanner], which runs anywhere and is tested, so what
 * is taken on trust here is as thin as the API allows. See [WslPaths] for why the path mapping is
 * not delegated to the platform either.
 */
internal object TaskWslSupport {

    /**
     * Rewrites [cmdLine] to run inside the distribution its own paths point at, or returns null
     * when they point at no distribution -- in which case the caller keeps the command line it
     * already had, untouched.
     *
     * That null is the whole contract: on Linux, on macOS, and on a Windows machine with a
     * Windows-local project, this returns on its first or second line and what runs is byte for
     * byte what ran before any of this existed.
     *
     * Throws [com.intellij.execution.ExecutionException] from the platform's own patching, which
     * the callers log and fall back from.
     */
    fun <T : GeneralCommandLine> patchForWsl(project: Project?, cmdLine: T): T? {
        if (!SystemInfo.isWindows) {
            return null
        }
        val plan =
            WslRunPlanner.plan(
                cmdLine.exePath,
                cmdLine.workDirectory?.path,
                cmdLine.parametersList.list,
            ) ?: return null

        val distribution =
            WslDistributionManager.getInstance().getOrCreateDistributionByMsId(plan.distributionId)
        val remoteWorkingDirectory =
            plan.remoteWorkingDirectory
                ?: plan.windowsWorkingDirectory?.let { windowsPath ->
                    // Only the distribution knows where Windows drives are mounted -- the /mnt root
                    // is configurable -- and getWslPath throws for a path it cannot place, which is
                    // why a directory in *another* distribution never reaches here (see
                    // WslRunPlanner.plan).
                    runCatching { distribution.getWslPath(Path.of(windowsPath)) }.getOrNull()
                }

        cmdLine.withExePath(plan.exePath)
        // Cleared rather than mapped: a UNC directory does not survive java.io.File, and wsl.exe
        // itself does not need one. The directory that matters is the one the login shell cds into,
        // which is the option below.
        cmdLine.withWorkDirectory(null as String?)
        cmdLine.parametersList.clearAll()
        cmdLine.addParameters(plan.parameters)

        // launchWithWslExe is forced on, and that is not a preference. Left at its default the
        // platform routes the command through IJent, which does not rewrite the command line at
        // all: it installs a process creator that runs only when createProcess() is called, and
        // that creator blocks on a coroutine. A run configuration's process is started on the EDT
        // (ExecutionManagerImpl -> DefaultRunProgramRunner -> CommandLineState.startProcess), where
        // blocking that way is forbidden -- the task still runs, but the IDE reports an
        // IllegalStateException every time one is started. Going through wsl.exe rewrites the
        // command line here and now, so starting it is an ordinary process start, safe wherever the
        // platform chooses to do it. It is the same escape the platform's own probes take
        // (readShellPath, getEnvironment) for the same reason.
        //
        // Everything else is left at its default on purpose. In particular passEnvVarsUsingInterop
        // stays off: with it off the platform turns cmdLine.environment into `export KEY='value'`
        // inside the login shell, which is what the configuration's environment variables are
        // supposed to mean, while turning it on would instead leak the Windows environment across
        // the boundary through WSLENV.
        val options = WSLCommandLineOptions().setLaunchWithWslExe(true)
        // The setter asserts on anything that is not distro-absolute. A null is fine and simply
        // leaves the login shell in its own home directory.
        remoteWorkingDirectory
            ?.takeIf { it.startsWith("/") }
            ?.let { options.setRemoteWorkingDirectory(it) }

        // Patching through wsl.exe reads the distribution's login shell, and that read is a
        // coroutine the platform runs blocking. Off the EDT it insists on something to cancel
        // against: on a bare pooled thread -- which is exactly what the Task Explorer discovers on
        // -- it throws "There is no ProgressIndicator or Job in this thread" and discovery fails
        // outright. An indicator is all it wants, and is what the platform installs around its own
        // blocking WSL calls.
        //
        // On the EDT the same read cannot block at all, so the platform answers /bin/sh and
        // computes the real shell in the background for next time. That costs one logged complaint
        // the very first time, and only if nothing has discovered a task yet -- discovery here
        // warms the same value.
        var patched: T? = null
        ProgressManager.getInstance()
            .runProcess(
                { patched = distribution.patchCommandLine(cmdLine, project, options) },
                EmptyProgressIndicator(),
            )
        return patched
    }
}
