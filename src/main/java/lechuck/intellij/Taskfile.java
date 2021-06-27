package lechuck.intellij;

import java.util.Map;

public class Taskfile {
    private Map<String, Object> tasks;

    public Map<String, Object> getTasks() {
        return tasks;
    }

    public void setTasks(Map<String, Object> tasks) {
        this.tasks = tasks;
    }
}
