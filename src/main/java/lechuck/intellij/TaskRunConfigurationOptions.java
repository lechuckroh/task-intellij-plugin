package lechuck.intellij;

import com.intellij.execution.configuration.EnvironmentVariablesData;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;
import com.intellij.openapi.components.StoredPropertyBase;

import java.util.Map;

import static org.apache.commons.lang3.StringUtils.defaultIfEmpty;

public class TaskRunConfigurationOptions extends RunConfigurationOptions {
    private final StoredProperty<String> taskPath = string("").provideDelegate(this, "taskPath");
    private final StoredProperty<String> taskfile = string("").provideDelegate(this, "taskfile");
    private final StoredProperty<String> task = string("").provideDelegate(this, "task");
    private final StoredProperty<String> arguments = string("").provideDelegate(this, "arguments");
    private final StoredPropertyBase<Map<String, String>> mapStoredPropertyBase = map();
    private final StoredProperty<Map<String, String>> environments = mapStoredPropertyBase.provideDelegate(this, "environments");

    public String getTaskPath() {
        return defaultIfEmpty(taskPath.getValue(this), "");
    }

    public void setTaskPath(String taskPath) {
        this.taskPath.setValue(this, defaultIfEmpty(taskPath, ""));
    }

    public String getTaskfile() {
        return defaultIfEmpty(taskfile.getValue(this), "");
    }

    public void setTaskfile(String taskfile) {
        this.taskfile.setValue(this, defaultIfEmpty(taskfile, ""));
    }

    public String getTask() {
        return defaultIfEmpty(task.getValue(this), "");
    }

    public void setTask(String task) {
        this.task.setValue(this, defaultIfEmpty(task, ""));
    }

    public String getArguments() {
        return defaultIfEmpty(arguments.getValue(this), "");
    }

    public void setArguments(String arguments) {
        this.arguments.setValue(this, defaultIfEmpty(arguments, ""));
    }

    public EnvironmentVariablesData getEnvironments() {
        var map = environments.getValue(this);
        return EnvironmentVariablesData.create(map, true);
    }

    public void setEnvironments(EnvironmentVariablesData env) {
        this.environments.setValue(this, env.getEnvs());
    }
}
