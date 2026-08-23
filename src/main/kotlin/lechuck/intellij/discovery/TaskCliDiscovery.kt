package lechuck.intellij.discovery

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger

/**
 * Discovers tasks by shelling out to the `task` CLI itself, the only thing that resolves
 * `includes:` (namespaced task names, and the line in whichever included Taskfile actually defines
 * them) the way running the task would. [TaskYamlDiscovery] is the fallback for when the binary
 * isn't installed.
 */
internal object TaskCliDiscovery {
    private val LOG = Logger.getInstance(TaskCliDiscovery::class.java)
    private const val TIMEOUT_MS = 5_000

    private val mapper =
        ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /**
     * Returns the tasks task itself would run from [taskfilePath], or the specific reason it
     * couldn't -- [TaskDiscovery] uses [CliOutcome.Failure] as the signal to fall back to the more
     * lenient (but includes:-blind) [TaskYamlDiscovery], which may still find something task itself
     * refused to run, while the failure's [CliOutcome.Failure.reason] lets a UI (the tool window)
     * explain why to the user instead of just showing an empty list.
     *
     * [taskExecutable] mirrors [lechuck.intellij.TaskRunConfiguration.taskPath]: empty means
     * "resolve `task` from the ambient PATH", same as running the configuration does.
     */
    fun discover(taskfilePath: String, taskExecutable: String = ""): CliOutcome {
        val commandLine =
            GeneralCommandLine(
                taskExecutable.ifEmpty { "task" },
                "--list-all",
                "--json",
                "--no-status",
                "-t",
                taskfilePath,
            )
        val handler =
            try {
                CapturingProcessHandler(commandLine)
            } catch (e: ExecutionException) {
                return CliOutcome.Failure(CliFailureReason.CLI_UNAVAILABLE)
            }
        val output = handler.runProcess(TIMEOUT_MS)
        if (output.isTimeout) {
            LOG.warn("task --list-all timed out for $taskfilePath")
            return CliOutcome.Failure(CliFailureReason.OTHER)
        }
        // Success is decided by whether stdout parses as the expected JSON, not by exit code alone:
        // task --list-all exits 1, not 0, when a Taskfile legitimately has zero tasks -- otherwise
        // indistinguishable at the exit-code level from a real failure, where stdout is empty and
        // parsing fails anyway.
        val tasks =
            try {
                mapper.readValue(output.stdout, CliTaskList::class.java).tasks.map {
                    it.toDiscoveredTask()
                }
            } catch (e: Exception) {
                null
            }
        if (tasks != null) {
            return CliOutcome.Success(tasks)
        }
        // task's own stderr already explains why
        LOG.warn("task --list-all exited ${output.exitCode} for $taskfilePath: ${output.stderr}")
        return CliOutcome.Failure(reasonForExitCode(output.exitCode))
    }

    // task's own documented exit codes (https://taskfile.dev/api/#exit-codes); anything else falls
    // under CliFailureReason.OTHER rather than being asserted about specifically.
    private fun reasonForExitCode(exitCode: Int): CliFailureReason =
        when (exitCode) {
            100 -> CliFailureReason.NO_TASKFILE
            109 -> CliFailureReason.PARSE_FAILED
            else -> CliFailureReason.OTHER
        }

    private class CliTaskList {
        var tasks: List<CliTask> = emptyList()
    }

    private class CliTask {
        var name: String = ""
        var desc: String = ""
        var summary: String = ""
        var aliases: List<String> = emptyList()
        var location: CliLocation? = null

        fun toDiscoveredTask() =
            DiscoveredTask().also {
                it.name = name
                it.desc = desc
                it.summary = summary
                it.aliases = aliases
                it.taskfilePath = location?.taskfile ?: ""
                it.line = location?.line
            }
    }

    private class CliLocation {
        var line: Int = 0
        var taskfile: String = ""
    }
}

internal sealed interface CliOutcome {
    data class Success(val tasks: List<DiscoveredTask>) : CliOutcome

    data class Failure(val reason: CliFailureReason) : CliOutcome
}

/**
 * Why [TaskCliDiscovery.discover] failed, distinct enough to explain to a user (see
 * [lechuck.intellij.explorer.TaskfileGroupNode]) rather than just falling back to
 * [TaskYamlDiscovery] silently.
 */
internal enum class CliFailureReason {
    /** The `task` binary itself couldn't be launched -- most likely not installed. */
    CLI_UNAVAILABLE,
    /** Exit 100: no Taskfile at the given path. */
    NO_TASKFILE,
    /** Exit 109: the Taskfile (or one it includes) failed to parse as YAML. */
    PARSE_FAILED,
    /** Any other non-zero exit, a timeout, or output that wasn't the JSON expected. */
    OTHER,
}
