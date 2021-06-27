package lechuck.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessHandlerFactory;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import lechuck.intellij.util.RegexUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;

class TaskCommandLineState extends CommandLineState {
    private final TaskRunConfiguration cfg;

    public TaskCommandLineState(@NotNull ExecutionEnvironment env, TaskRunConfiguration cfg) {
        super(env);
        this.cfg = cfg;
    }

    @Override
    @NotNull
    protected ProcessHandler startProcess() throws ExecutionException {
        var processHandler = ProcessHandlerFactory.getInstance()
                .createColoredProcessHandler(createGeneralCommandLine());

        ProcessTerminatedListener.attach(processHandler);

        return processHandler;
    }

    private GeneralCommandLine createGeneralCommandLine() {
        var options = cfg.getOptions();

        var cmd = new ArrayList<String>();
        cmd.add("task");

        // taskfile
        var taskfile = options.getTaskfile();
        if (!taskfile.isEmpty()) {
            cmd.add("--taskfile");
            cmd.add(taskfile);
        }

        // task
        cmd.add(options.getTask());

        // arguments
        var arguments = options.getArguments();
        var argumentList = RegexUtil.splitBySpacePreservingQuotes(arguments);
        if (!argumentList.isEmpty()) {
            cmd.add("--");
            cmd.addAll(argumentList);
        }

        // environment variables
        var envMap = new HashMap<String, String>();

        return new GeneralCommandLine(cmd).withEnvironment(envMap);
    }
}