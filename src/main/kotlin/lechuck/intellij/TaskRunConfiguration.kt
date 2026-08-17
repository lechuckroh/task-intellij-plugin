package lechuck.intellij

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.*
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project
import com.intellij.terminal.TerminalExecutionConsole
import java.io.File
import lechuck.intellij.util.StringUtil.splitVars
import lechuck.intellij.vars.VariablesData
import org.jdom.Element

class TaskRunConfiguration(project: Project, factory: TaskConfigurationFactory, name: String) :
    LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var taskPath = ""
    var filename = ""
    var task = ""
    var arguments = ""
    var workingDirectory = ""
    var environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT
    var variables: VariablesData = VariablesData.DEFAULT
    var pty = true

    private companion object {
        const val TASKFILE = "taskfile"
        const val TASKPATH = "taskPath"
        const val FILENAME = "filename"
        const val TASK = "task"
        const val WORKING_DIRECTORY = "workingDirectory"
        const val ARGUMENTS = "arguments"
        const val PTY = "pty"
        const val TERMINAL_COLUMNS = 120
        const val TERMINAL_ROWS = 30
    }

    override fun checkConfiguration() {
        if (task.isEmpty()) {
            throw RuntimeConfigurationError("Task is not set")
        }
    }

    override fun getConfigurationEditor() = TaskRunConfigurationEditor(project)

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        val child = element.getOrCreateChild(TASKFILE)
        child.setAttribute(TASKPATH, taskPath)
        child.setAttribute(FILENAME, filename)
        child.setAttribute(TASK, task)
        child.setAttribute(WORKING_DIRECTORY, workingDirectory)
        child.setAttribute(ARGUMENTS, arguments)
        child.setAttribute(PTY, if (pty) "true" else "false")
        environmentVariables.writeExternal(child)
        variables.writeExternal(child)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)

        readExternalV1(element)

        val taskfileElem = element.getChild(TASKFILE)
        if (taskfileElem != null) {
            readExternalV13(taskfileElem)

            taskPath = taskfileElem.getAttributeValue(TASKPATH) ?: ""
            filename = taskfileElem.getAttributeValue(FILENAME) ?: ""
            task = taskfileElem.getAttributeValue(TASK) ?: ""
            workingDirectory = taskfileElem.getAttributeValue(WORKING_DIRECTORY) ?: ""
            arguments = taskfileElem.getAttributeValue(ARGUMENTS) ?: ""
            pty = taskfileElem.getAttributeValue(PTY)?.toBooleanStrictOrNull() ?: true
            environmentVariables = EnvironmentVariablesData.readExternal(taskfileElem)

            val variablesRead = VariablesData.readExternal(taskfileElem)
            variables =
                if (variables.vars.isEmpty()) {
                    variablesRead
                } else {
                    VariablesData.create(variablesRead.vars + variables.vars)
                }
        }
    }

    /** read v1.0 format */
    private fun readExternalV1(element: Element) {
        val list = element.getChildren("option")
        val valueMap =
            list.associate { option: Element ->
                val name = option.getAttributeValue("name")
                val value = option.getAttributeValue("value")
                name to value
            }
        taskPath = valueMap["taskPath"] ?: ""
        task = valueMap["task"] ?: ""
        filename = valueMap["taskfile"] ?: ""
        arguments = valueMap["arguments"] ?: ""
    }

    /** read v1.3 format */
    private fun readExternalV13(taskfileElem: Element) {
        val variablesText = taskfileElem.getAttributeValue("variables", "")
        val vars = splitVars(variablesText)
        if (vars.isNotEmpty()) {
            variables = VariablesData.create(vars)
        }
    }

    override fun getState(
        executor: Executor,
        executionEnvironment: ExecutionEnvironment,
    ): RunProfileState {
        return object : CommandLineState(executionEnvironment) {
            override fun startProcess(): ProcessHandler {
                val handler = KillableProcessHandler(buildCommandLine())
                ProcessTerminatedListener.attach(handler)
                return handler
            }

            override fun createConsole(executor: Executor): ConsoleView =
                TerminalExecutionConsole(project, TERMINAL_COLUMNS, TERMINAL_ROWS, null)
                    .withConvertLfToCrlfForNonPtyProcess(true)
        }
    }

    internal fun buildCommandLine(): GeneralCommandLine {
        val params = ParametersList()

        // taskfile
        val macroManager = PathMacroManager.getInstance(project)
        val taskfilePath = macroManager.expandPath(filename)
        if (taskfilePath.isNotEmpty()) {
            params.addAll("--taskfile", taskfilePath)
        }

        // task
        if (task.isNotEmpty()) {
            params.addParametersString(task)
        }

        // variables
        variables.vars.forEach { (key, value) -> params.add("$key=$value") }

        // arguments
        if (arguments.isNotEmpty()) {
            params.add("--")
            params.addParametersString(arguments)
        }

        // working directory. Left unset, task inherits the IDE's own and searches upwards from
        // there for an unrelated Taskfile. A directory derived from the taskfile is therefore
        // only usable when absolute: task resolves the taskfile path against this directory, so
        // a relative segment would apply twice. An explicitly set workingDirectory has no such
        // interaction and is passed through as written.
        val workDirectory =
            when {
                workingDirectory.isNotEmpty() -> macroManager.expandPath(workingDirectory)
                else -> File(taskfilePath).parent?.takeIf { File(it).isAbsolute }
            } ?: project.basePath

        // environment variables
        val parentEnvType =
            if (environmentVariables.isPassParentEnvs) {
                GeneralCommandLine.ParentEnvironmentType.CONSOLE
            } else {
                GeneralCommandLine.ParentEnvironmentType.NONE
            }

        // build cmd
        val cmdLine =
            if (pty) {
                PtyCommandLine()
                    .withConsoleMode(false)
                    .withInitialColumns(TERMINAL_COLUMNS)
                    .withInitialRows(TERMINAL_ROWS)
            } else {
                GeneralCommandLine()
            }
        return cmdLine
            .withExePath(taskPath.ifEmpty { "task" })
            .withWorkDirectory(workDirectory)
            .withEnvironment(environmentVariables.envs)
            .withParentEnvironmentType(parentEnvType)
            .withParameters(params.list)
    }
}
