package lechuck.intellij

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.getOrCreate
import lechuck.intellij.util.StringUtil.splitVars
import lechuck.intellij.vars.VariablesData
import org.jdom.Element
import java.io.File

class TaskRunConfiguration(project: Project, factory: TaskConfigurationFactory, name: String) :
    LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var taskPath = ""
    var filename = ""
    var task = ""
    var arguments = ""
    var workingDirectory = ""
    var environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT
    var variables: VariablesData = VariablesData.DEFAULT
    var pty = false

    private companion object {
        const val TASKFILE = "taskfile"
        const val TASKPATH = "taskPath"
        const val FILENAME = "filename"
        const val TASK = "task"
        const val WORKING_DIRECTORY = "workingDirectory"
        const val ARGUMENTS = "arguments"
        const val PTY = "pty"
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
            pty = taskfileElem.getAttributeValue(PTY) == "true"
            environmentVariables = EnvironmentVariablesData.readExternal(taskfileElem)

            val variablesRead = VariablesData.readExternal(taskfileElem)
            variables = if (variables.vars.isEmpty()) {
                variablesRead
            } else {
                VariablesData.create(variablesRead.vars + variables.vars)
            }
        }
    }

    /**
     * read v1.0 format
     */
    private fun readExternalV1(element: Element) {
        val list = element.getChildren("option")
        val valueMap = list.associate { option: Element ->
            val name = option.getAttributeValue("name")
            val value = option.getAttributeValue("value")
            name to value
        }
        taskPath = valueMap["taskPath"] ?: ""
        task = valueMap["task"] ?: ""
        filename = valueMap["taskfile"] ?: ""
        arguments = valueMap["arguments"] ?: ""
    }

    /**
     * read v1.3 format
     */
    private fun readExternalV13(taskfileElem: Element) {
        val variablesText = taskfileElem.getAttributeValue("variables", "")
        val vars = splitVars(variablesText)
        if (vars.isNotEmpty()) {
            variables = VariablesData.create(vars)
        }
    }

    override fun getState(executor: Executor, executionEnvironment: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(executionEnvironment) {
            override fun startProcess(): ProcessHandler {
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
                val vars = variables.vars.toMutableMap()
                vars.forEach { (key, value) ->
                    params.add("$key=\"$value\"")
                }

                // arguments
                if (arguments.isNotEmpty()) {
                    params.add("--")
                    params.addParametersString(arguments)
                }

                // working directory
                val workDirectory = if (workingDirectory.isNotEmpty()) {
                    macroManager.expandPath(workingDirectory)
                } else {
                    File(taskfilePath).parent
                }

                // environment variables
                val parentEnvs =
                    if (environmentVariables.isPassParentEnvs) EnvironmentUtil.getEnvironmentMap() else emptyMap<String, String>()
                val envs = parentEnvs + environmentVariables.envs.toMutableMap()

                // build cmd
                val command = arrayOf(taskPath.ifEmpty { "task" }) + params.array
                val cmdLine = if (pty) PtyCommandLine() else GeneralCommandLine()
                val cmd = cmdLine
                    .withExePath(command[0])
                    .withWorkDirectory(workDirectory)
                    .withEnvironment(envs)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                    .withParameters(command.slice(1 until command.size))

                val processHandler = ColoredProcessHandler(cmd)
                processHandler.setShouldKillProcessSoftly(true)
                ProcessTerminatedListener.attach(processHandler)
                return processHandler
            }
        }
    }
}
