package lechuck.intellij

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project


open class TaskRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<TaskRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): TaskRunConfigurationOptions {
        return super.getOptions() as TaskRunConfigurationOptions
    }

    fun getScriptName(): String {
        return options.getTaskFilename()
    }

    fun setScriptName(scriptName: String) {
        options.setTaskFilename(scriptName)
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return TaskSettingsEditor()
    }

    override fun checkConfiguration() {}

    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState {
        return object : CommandLineState(env) {
            override fun startProcess(): ProcessHandler {
                val commandLine = GeneralCommandLine(options.getTaskFilename())
                val processHandler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine)
                ProcessTerminatedListener.attach(processHandler)
                return processHandler
            }
        }
    }
}