package lechuck.intellij;

import java.util.Collections;
import java.util.Map;

public class Taskfile {
    private Map<String, Object> tasks;

    public Map<String, Object> getTasks() {
        return Collections.unmodifiableMap(tasks);
    }

    // TODO remove?
    @SuppressWarnings("unused")
    public void setTasks(Map<String, Object> tasks) {
        this.tasks = tasks;
    }
}
