package lechuck.intellij

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment

class TaskCommandLineState(
    env: ExecutionEnvironment,
    private val cfg: TaskRunConfiguration
) : CommandLineState(env) {

    override fun startProcess(): ProcessHandler {
        val processHandler = ProcessHandlerFactory.getInstance()
            .createColoredProcessHandler(createGeneralCommandLine())

        ProcessTerminatedListener.attach(processHandler)

        return processHandler
    }

    private fun createGeneralCommandLine(): GeneralCommandLine {
        val options = cfg.options

        val cmd = mutableListOf("task")

        // taskfile
        val taskfile = options.getTaskfile()
        if (taskfile.isNotEmpty()) {
            cmd.add("--taskfile")
            cmd.add(taskfile)
        }

        // task
        cmd.add(options.getTask())

        // environment variables
        val envMap = mutableMapOf<String, String>()

        return GeneralCommandLine(cmd)
            .withEnvironment(envMap)
    }
}