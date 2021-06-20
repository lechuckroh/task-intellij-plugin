package lechuck.intellij;

import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.openapi.components.StoredProperty;

public class TaskRunConfigurationOptions extends RunConfigurationOptions {
    private final StoredProperty<String> myTaskfile = string("").provideDelegate(this, "taskfile");
    private final StoredProperty<String> myTask = string("").provideDelegate(this, "task");

    public String getTaskfile() {
        return myTaskfile.getValue(this);
    }

    public void setTaskfile(String filename) {
        myTaskfile.setValue(this, filename);
    }

    public String getTask() {
        return myTask.getValue(this);
    }

    public void  setTask(String name) {
        myTask.setValue(this, name);
    }
}
