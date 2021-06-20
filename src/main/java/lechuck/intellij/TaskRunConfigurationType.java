package lechuck.intellij;

import com.intellij.execution.configurations.ConfigurationTypeBase;

public class TaskRunConfigurationType extends ConfigurationTypeBase {
    public static final String ID = "TaskRunConfiguration";

    public TaskRunConfigurationType() {
        super(ID, "Taskfile", "Taskfile run configuration type", TaskPluginIcons.Task);
        addFactory(new TaskConfigurationFactory(this));
    }
}
