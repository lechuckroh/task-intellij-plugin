package lechuck.intellij.wsl

/**
 * The Windows-UNC to distro-Linux path mapping, as pure string arithmetic.
 *
 * It deliberately re-implements what [com.intellij.execution.wsl.WslPath] parses rather than
 * calling it, for two reasons that both matter here. The platform's parser answers "not a WSL path"
 * on every non-Windows OS by design, so it cannot be asserted about on the Linux box that runs the
 * tests -- and the mapping is the half of this feature actually worth testing. And the reverse
 * direction has to reuse the prefix the caller already saw (see [hostMapper]), which the platform
 * does not offer: [com.intellij.execution.wsl.WSLDistribution.getWindowsPath] rebuilds the path
 * with the legacy `\\wsl$\` prefix whatever the project is mounted under, producing strings that
 * look right and match nothing.
 *
 * [TaskWslSupport] holds the matching OS guard, so nothing in this file is Windows-only.
 */
internal object WslPaths {

    /**
     * Both roots Windows exposes a distribution under. `\\wsl$\` is the legacy one and
     * `\\wsl.localhost\` the modern one; which one a path carries depends on the Windows build and
     * on how the project happened to be opened, not on anything this plugin controls, so both are
     * recognised regardless of which one this machine prefers.
     */
    private val PREFIXES = listOf("//wsl.localhost/", "//wsl\$/")

    /** A WSL UNC path split at the distribution boundary; [root] + [linuxPath] rebuilds it. */
    internal data class Unc(
        /** `//wsl.localhost/Ubuntu` -- system-independent separators, no trailing slash. */
        val root: String,
        /** `Ubuntu`, `Ubuntu-24.04`, `docker-desktop`. */
        val distributionId: String,
        /** `/home/x/Taskfile.yml` -- always starts with `/`. */
        val linuxPath: String,
    )

    /**
     * Null for anything that is not a path inside a WSL distribution, a bare distribution root
     * included -- `//wsl.localhost/Ubuntu` names the distribution, not a path in it, which is the
     * same boundary the platform's own parser draws.
     *
     * Both separators are accepted because both spellings reach this code: the VFS hands out
     * `//wsl.localhost/...`, while `java.io.File.getParent` -- which
     * [lechuck.intellij.TaskRunConfiguration.buildHostCommandLine] derives the working directory
     * with -- hands back `\\wsl.localhost\...` for the very same file.
     */
    fun parse(path: String): Unc? {
        val normalized = path.replace('\\', '/')
        val prefix =
            PREFIXES.firstOrNull { normalized.startsWith(it, ignoreCase = true) } ?: return null
        val separator = normalized.indexOf('/', prefix.length)
        if (separator <= prefix.length) {
            return null
        }
        return Unc(
            root = normalized.substring(0, separator),
            distributionId = normalized.substring(prefix.length, separator),
            linuxPath = normalized.substring(separator),
        )
    }

    /** The inverse of [parse], for a [root] that came out of one. */
    fun toHost(root: String, linuxPath: String): String = root + linuxPath

    /**
     * Maps paths reported from inside a distribution back onto the host spelling the rest of the
     * plugin speaks -- forward slashes, and the same prefix the project itself is mounted under,
     * taken from [reference] rather than reconstructed.
     *
     * Returns the identity function when [reference] is not a WSL path, so a caller can apply it
     * unconditionally instead of branching.
     */
    fun hostMapper(reference: String): (String) -> String {
        val root = parse(reference)?.root ?: return { it }
        return { path ->
            // A path that is already host-shaped (`//wsl.localhost/...`, `C:/...`) or relative is
            // left alone: only a distro-absolute path needs the root prepended.
            if (path.startsWith("/") && !path.startsWith("//")) toHost(root, path) else path
        }
    }
}
