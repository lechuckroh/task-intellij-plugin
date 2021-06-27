package lechuck.intellij;

import com.intellij.execution.Executor;
import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.configurations.*;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class TaskRunConfiguration extends RunConfigurationBase<TaskRunConfigurationOptions> {

    public TaskRunConfiguration(Project project, ConfigurationFactory factory, String name) {
        super(project, factory, name);
    }

    @Override
    @NotNull
    public TaskRunConfigurationOptions getOptions() {
        return (TaskRunConfigurationOptions) super.getOptions();
    }

    public String getTaskfile() {
        return getOptions().getTaskfile();
    }

    public void setTaskfile(String filename) {
        getOptions().setTaskfile(filename);
    }

    public String getTask() {
        return getOptions().getTask();
    }

    public void setTask(String task) {
        getOptions().setTask(task);
    }

    public String getArguments() {
        return getOptions().getArguments();
    }

    public void setArguments(String arguments) {
        getOptions().setArguments(arguments);
    }

    public EnvironmentVariablesData getEnvironments() {
        return getOptions().getEnvironments();
    }

    public void setEnvironments(EnvironmentVariablesData env) {
        getOptions().setEnvironments(env);
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new TaskSettingsEditor(this.getProject());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        var task = getTask();
        if (StringUtils.isEmpty(task)) {
            throw new RuntimeConfigurationError("Task is not set");
        }
    }

    @Override
    public @Nullable RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) {
        return new TaskCommandLineState(env, this);
    }
}
