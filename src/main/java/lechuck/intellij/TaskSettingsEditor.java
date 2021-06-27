package lechuck.intellij;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.intellij.execution.configuration.EnvironmentVariablesComponent;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.PathMacros;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.FixedSizeButton;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.TextFieldWithAutoCompletion;
import com.intellij.ui.components.fields.ExpandableTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class TaskSettingsEditor extends SettingsEditor<TaskRunConfiguration> {
    private JPanel panel;
    private final Project project;
    private final TextFieldWithBrowseButton filenameField;
    private final TextFieldWithAutoCompletion.StringsCompletionProvider taskCompletionProvider;
    private final TextFieldWithAutoCompletion<String> taskField;
    private final ExpandableTextField argumentsField;
    private final EnvironmentVariablesComponent envVarsComponent;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public TaskSettingsEditor(@NotNull Project project) {
        this.project = project;
        this.filenameField = new TextFieldWithBrowseButton();
        this.taskCompletionProvider = new TextFieldWithAutoCompletion.StringsCompletionProvider(List.of(), null);
        this.taskField = new TextFieldWithAutoCompletion<>(project, taskCompletionProvider, true, "");
        this.argumentsField = new ExpandableTextField();
        this.envVarsComponent = new EnvironmentVariablesComponent();

        this.filenameField.addBrowseFolderListener("Taskfile", "Select Taskfile.yml to run", project, new TaskfileFileChooserDescriptor());
        this.filenameField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                updateTargetCompletion(filenameField.getText());
            }
        });
    }

    private void updateTargetCompletion(String filename) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filename);
        if (file != null) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                taskCompletionProvider.setItems(findTasks(psiFile));
                return;
            }
        }
        taskCompletionProvider.setItems(List.of());
    }


    private Collection<String> findTasks(PsiFile file) {
        try (InputStream is = file.getVirtualFile().getInputStream()) {
            Taskfile taskfile = mapper.readValue(is, Taskfile.class);
            Map<String, Object> tasks = taskfile.getTasks();
            return tasks == null ? List.of() : tasks.keySet();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    @Override
    protected void resetEditorFrom(TaskRunConfiguration cfg) {
        filenameField.setText(cfg.getTaskfile());
        taskField.setText(cfg.getTask());
        argumentsField.setText(cfg.getArguments());
        envVarsComponent.setEnvData(cfg.getEnvironments());
    }

    @Override
    protected void applyEditorTo(@NotNull TaskRunConfiguration cfg) {
        cfg.setTaskfile(filenameField.getText());
        cfg.setTask(taskField.getText());
        cfg.setArguments(argumentsField.getText());
        cfg.setEnvironments(envVarsComponent.getEnvData());
    }

    @NotNull
    @Override
    protected JComponent createEditor() {
        if (panel == null) {
            panel = FormBuilder.createFormBuilder()
                    .setAlignLabelOnRight(false)
                    .setHorizontalGap(UIUtil.DEFAULT_HGAP)
                    .setVerticalGap(UIUtil.DEFAULT_VGAP)
                    .addLabeledComponent("Taskfile", filenameField)
                    .addLabeledComponent("Task", taskField)
                    .addComponent(LabeledComponent.create(argumentsField, "CLI arguments"))
                    .getPanel();
        }
        return panel;
    }

    private JComponent createComponentWithMacroBrowse(TextFieldWithBrowseButton textAccessor) {
        var button = new FixedSizeButton(textAccessor);
        button.setIcon(AllIcons.Actions.ListFiles);
        button.addActionListener(e -> {
            var userMacroNames = new ArrayList<>(PathMacros.getInstance().getUserMacroNames());
            JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(userMacroNames)
                    .setItemChosenCallback(item -> textAccessor.setText("$$item$"))
                    .setMovable(false)
                    .setResizable(false)
                    .createPopup()
                    .showUnderneathOf(button);
        });
        var panel = new JPanel(new BorderLayout());
        panel.add(textAccessor, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }
}
