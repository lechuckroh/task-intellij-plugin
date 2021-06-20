package lechuck.intellij;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.components.BaseState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TaskConfigurationFactory extends ConfigurationFactory {

    public TaskConfigurationFactory(ConfigurationType type) {
        super(type);
    }

    @Override
    public @NotNull @NonNls String getId() {
        return TaskRunConfigurationType.ID;
    }

    @Override
    public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new TaskRunConfiguration(project, this, "Task");
    }

    @Override
    public @Nullable Class<? extends BaseState> getOptionsClass() {
        return TaskRunConfigurationOptions.class;
    }
}
