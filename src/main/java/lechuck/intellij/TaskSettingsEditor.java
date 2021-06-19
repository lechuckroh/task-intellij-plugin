package lechuck.intellij;

import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public class TaskSettingsEditor extends SettingsEditor<TaskRunConfiguration> {

    private JPanel panel;
    private LabeledComponent<TextFieldWithBrowseButton> taskFilename;

    @Override
    protected void resetEditorFrom(TaskRunConfiguration cfg) {
        taskFilename.getComponent().setText(cfg.getScriptName());
    }

    @Override
    protected void applyEditorTo(@NotNull TaskRunConfiguration cfg) {
        cfg.setScriptName(taskFilename.getComponent().getText());
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        return panel;
    }

    private void createUIComponents() {
        taskFilename = new LabeledComponent<>();
        taskFilename.setComponent(new TextFieldWithBrowseButton());
    }

}
