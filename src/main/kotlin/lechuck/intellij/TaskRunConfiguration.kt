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
    var variables = ""
    var pty = false

    private companion object {
        const val TASKFILE = "taskfile"
        const val TASKPATH = "taskPath"
        const val FILENAME = "filename"
        const val TASK = "task"
        const val WORKING_DIRECTORY = "workingDirectory"
        const val ARGUMENTS = "arguments"
        const val VARIABLES = "variables"
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
        val child = element.getOrCreate(TASKFILE)
        child.setAttribute(TASKPATH, taskPath)
        child.setAttribute(FILENAME, filename)
        child.setAttribute(TASK, task)
        child.setAttribute(WORKING_DIRECTORY, workingDirectory)
        child.setAttribute(ARGUMENTS, arguments)
        child.setAttribute(VARIABLES, variables)
        child.setAttribute(PTY, if (pty) "true" else "false")
        environmentVariables.writeExternal(child)
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)

        // support v1 format
        readExternalV1(element)

        val child = element.getChild(TASKFILE)
        if (child != null) {
            taskPath = child.getAttributeValue(TASKPATH) ?: ""
            filename = child.getAttributeValue(FILENAME) ?: ""
            task = child.getAttributeValue(TASK) ?: ""
            workingDirectory = child.getAttributeValue(WORKING_DIRECTORY) ?: ""
            arguments = child.getAttributeValue(ARGUMENTS) ?: ""
            pty = child.getAttributeValue(PTY) == "true"
            environmentVariables = EnvironmentVariablesData.readExternal(child)
            variables = child.getAttributeValue(VARIABLES) ?: ""
        }
    }

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
                if (variables.isNotEmpty()) {
                    val varMap = splitVars(variables)
                    if (varMap.isNotEmpty()) {
                        varMap.forEach { (key, value) ->
                            params.add("""$key="$value"""")
                        }
                    }
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
