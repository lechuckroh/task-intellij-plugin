package lechuck.intellij;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.ide.util.TreeFileChooserFactory;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.TextFieldWithAutoCompletion;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.Arrays;
import java.util.Collection;

public class TaskSettingsEditor extends SettingsEditor<TaskRunConfiguration> {

    private JPanel panel;
    private LabeledComponent<TextFieldWithBrowseButton> taskfile;
    private LabeledComponent<TextFieldWithAutoCompletion<String>> task;
    private TaskRunConfiguration currentCfg;

    @Override
    protected void resetEditorFrom(TaskRunConfiguration cfg) {
        taskfile.getComponent().setText(cfg.getTaskfile());
        task.getComponent().setText(cfg.getTask());
        this.currentCfg = cfg;
    }

    @Override
    protected void applyEditorTo(@NotNull TaskRunConfiguration cfg) {
        cfg.setTaskfile(taskfile.getComponent().getText());
        cfg.setTask(task.getComponent().getText());
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        return panel;
    }

    private void createUIComponents() {
        taskfile = new LabeledComponent<>();
        taskfile.setComponent(createTaskfileComponent());
        task = new LabeledComponent<>();
        task.setComponent(createTaskComponent());
    }

    private TextFieldWithBrowseButton createTaskfileComponent() {
        return new TextFieldWithBrowseButton(e -> {
            var project = getProject();
            if (project == null) {
                return;
            }
            var chooser = TreeFileChooserFactory.getInstance(getProject())
                    .createFileChooser("Select Taskfile", null, null, null);
            chooser.showDialog();
            var selectedFile = chooser.getSelectedFile();
            if (selectedFile != null) {
                taskfile.getComponent().setText(selectedFile.getVirtualFile().getPath());
            }
        });
    }

    private TextFieldWithAutoCompletion<String> createTaskComponent() {
        var listProvider = new TextFieldWithAutoCompletion.StringsCompletionProvider(null, null) {
            @NotNull
            @Override
            public Collection<String> getItems(String prefix, boolean cached, CompletionParameters parameters) {
                setItems(Arrays.asList("hello", "world"));
                return super.getItems(prefix, cached, parameters);
            }
        };
        return new TextFieldWithAutoCompletion<>(getProject(), listProvider, true, "");
    }

    private Project getProject() {
        if (currentCfg != null) {
            return currentCfg.getProject();
        }
        return null;
    }
}
