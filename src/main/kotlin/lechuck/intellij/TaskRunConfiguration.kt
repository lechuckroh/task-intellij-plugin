package lechuck.intellij

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project


open class TaskRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<TaskRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): TaskRunConfigurationOptions {
        return super.getOptions() as TaskRunConfigurationOptions
    }

    fun getTaskfile(): String {
        return options.getTaskfile()
    }

    fun setTaskfile(filename: String) {
        options.setTaskfile(filename)
    }

    fun getTask(): String {
        return options.getTask()
    }

    fun setTask(task: String) {
        options.setTask(task)
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return TaskSettingsEditor()
    }

    @Throws(RuntimeConfigurationException::class)
    override fun checkConfiguration() {
        val task = getTask()
        if (task.isEmpty()) {
            throw RuntimeConfigurationError("Task is not set")
        }
    }

    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState {
        return TaskCommandLineState(env, this)
    }
}