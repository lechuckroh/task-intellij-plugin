package lechuck.intellij.wsl

import com.intellij.openapi.util.io.OSAgnosticPathUtil

/**
 * What [TaskWslSupport] is about to do to a command line, decided without touching the WSL API so
 * that the decisions -- which distribution, which binary, which paths -- can be asserted about on
 * any operating system.
 */
internal data class WslRunPlan(
    /** `Ubuntu`, as `wsl.exe -d` names it. */
    val distributionId: String,
    /** The UNC root the paths came in under, kept so results can be mapped back the same way. */
    val uncRoot: String,
    /** Already a Linux path or a bare name; never a Windows one. */
    val exePath: String,
    /** Ready for `WSLCommandLineOptions.setRemoteWorkingDirectory`. */
    val remoteWorkingDirectory: String?,
    /**
     * A Windows-local working directory (`C:\tmp`) that only the distribution itself can map, since
     * the `/mnt` root is configurable. Never set together with [remoteWorkingDirectory].
     */
    val windowsWorkingDirectory: String?,
    /** argv with every path of this distribution rewritten to its Linux form. */
    val parameters: List<String>,
    /**
     * A `taskPath` naming a Windows binary, which was dropped in favour of `task` from the
     * distribution's own PATH. Carried so
     * [lechuck.intellij.TaskRunConfiguration.checkConfiguration] can say so out loud rather than
     * leaving the user wondering which binary ran.
     */
    val ignoredWindowsExecutable: String?,
)

internal object WslRunPlanner {

    const val DEFAULT_EXECUTABLE = "task"

    /**
     * Returns null -- "this is not a WSL run, leave the command line exactly as it is" -- unless
     * some path in it points inside a distribution.
     *
     * Takes strings rather than a `GeneralCommandLine` so it stays free of the platform, and holds
     * no `SystemInfo` check on purpose: that guard belongs to [TaskWslSupport], which is the only
     * caller, so this function can be exercised anywhere.
     */
    fun plan(exePath: String, workDirectory: String?, parameters: List<String>): WslRunPlan? {
        // The Taskfile decides which distribution runs the task, not the working directory: its
        // `cmds:` are what has to execute there. Only if no argument is a WSL path at all does the
        // working directory get a say.
        val fromParameters = parameters.firstNotNullOfOrNull { WslPaths.parse(it) }
        val fromWorkDirectory = workDirectory?.let { WslPaths.parse(it) }
        val selected = fromParameters ?: fromWorkDirectory ?: return null

        val inSelectedDistribution = { path: String ->
            WslPaths.parse(path)?.takeIf { it.distributionId.equals(selected.distributionId, true) }
        }

        // Rewriting by shape rather than by flag covers `--taskfile <path>` and `-t <path>` with
        // one rule and no knowledge of either, and cannot misfire on a non-path argument: nothing
        // but a path starts with `\\wsl.localhost\`.
        val mappedParameters = parameters.map { inSelectedDistribution(it)?.linuxPath ?: it }

        val mappedExecutable = inSelectedDistribution(exePath)?.linuxPath
        val windowsExecutable =
            exePath.isNotEmpty() && mappedExecutable == null && isWindowsPath(exePath)
        val resolvedExecutable =
            when {
                mappedExecutable != null -> mappedExecutable
                // Empty means "resolve task from PATH", which inside the distribution is the Linux
                // PATH -- the common case, and the only one the gutter, the Task Explorer and Run
                // Anything ever produce, since none of them sets taskPath.
                exePath.isEmpty() -> DEFAULT_EXECUTABLE
                // A Windows binary cannot run in the distribution, and neither can one belonging to
                // a different distribution. Fall back to the distro's own task rather than failing.
                windowsExecutable -> DEFAULT_EXECUTABLE
                // A Linux path, or a bare name the Linux PATH can resolve: the user meant it.
                else -> exePath
            }

        val mappedWorkDirectory = workDirectory?.let(inSelectedDistribution)?.linuxPath
        val windowsWorkDirectory = workDirectory?.takeIf {
            mappedWorkDirectory == null && OSAgnosticPathUtil.isAbsoluteDosPath(it)
        }
        val remoteWorkDirectory =
            mappedWorkDirectory
                // Already a Linux path. The WSL check is not redundant: a UNC path of *another*
                // distribution also starts with a separator, and using it as a Linux path would
                // silently run the task in a directory that does not exist.
                ?: workDirectory?.takeIf {
                    windowsWorkDirectory == null && it.startsWith("/") && WslPaths.parse(it) == null
                }
                // Nothing usable -- a blank directory, or one in another distribution. The
                // Taskfile's own parent is where task would have run anyway, and leaving it unset
                // would land the run in the login shell's home directory instead.
                ?: fromParameters?.linuxPath?.let(::parentOf).takeIf {
                    windowsWorkDirectory == null
                }

        return WslRunPlan(
            distributionId = selected.distributionId,
            uncRoot = selected.root,
            exePath = resolvedExecutable,
            remoteWorkingDirectory = remoteWorkDirectory,
            windowsWorkingDirectory = windowsWorkDirectory,
            parameters = mappedParameters,
            ignoredWindowsExecutable = exePath.takeIf { windowsExecutable },
        )
    }

    /**
     * True for a path this JVM can only mean as a Windows one. A UNC path of another distribution
     * counts: it is as unusable inside this distribution as `C:\tools\task.exe` is.
     */
    private fun isWindowsPath(path: String): Boolean =
        OSAgnosticPathUtil.isAbsoluteDosPath(path) ||
            path.contains('\\') ||
            WslPaths.parse(path) != null

    private fun parentOf(linuxPath: String): String =
        linuxPath.substringBeforeLast('/').ifEmpty { "/" }
}
