package lechuck.intellij.vars;

import com.intellij.execution.ExecutionBundle;
import com.intellij.execution.util.ListTableWithButtons;
import com.intellij.execution.util.StringWithNewLinesCellEditor;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.ide.PasteProvider;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.table.TableView;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.ListTableModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VariablesTable extends ListTableWithButtons<Variable> {
    private CopyPasteProviderPanel myPanel;
    private boolean myPasteEnabled = false;

    public VariablesTable() {
        getTableView().getEmptyText().setText(ExecutionBundle.message("empty.text.no.variables"));
        AnAction copyAction = ActionManager.getInstance().getAction(IdeActions.ACTION_COPY);
        if (copyAction != null) {
            copyAction.registerCustomShortcutSet(copyAction.getShortcutSet(), getTableView()); // no need to add in popup menu
        }
        AnAction pasteAction = ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE);
        if (pasteAction != null) {
            pasteAction.registerCustomShortcutSet(pasteAction.getShortcutSet(), getTableView()); // no need to add in popup menu
        }
    }

    public void setPasteActionEnabled(boolean enabled) {
        myPasteEnabled = enabled;
    }

    @Override
    protected ListTableModel createListModel() {
        return new ListTableModel(new NameColumnInfo(), new ValueColumnInfo());
    }

    public void editVariableName(final Variable variable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            final Variable actualVar = ContainerUtil.find(getElements(),
                    item -> StringUtil.equals(variable.getName(), item.getName()));
            if (actualVar == null) {
                return;
            }

            setSelection(actualVar);
            editSelection(0);
        });
    }

    public List<Variable> getVariables() {
        return getElements();
    }

    @Override
    public JComponent getComponent() {
        if (myPanel == null) {
            myPanel = new CopyPasteProviderPanel(super.getComponent());
        }
        return myPanel;
    }

    @Override
    protected Variable createElement() {
        return new Variable("", "");
    }

    @Override
    protected boolean isEmpty(Variable element) {
        return element.getName().isEmpty() && element.getValue().isEmpty();
    }


    @Override
    protected Variable cloneElement(Variable variable) {
        return variable.clone();
    }

    @Override
    protected boolean canDeleteElement(Variable selection) {
        return true;
    }

    protected class NameColumnInfo extends ElementsColumnInfoBase<Variable> {
        public NameColumnInfo() {
            super("Name");
        }

        @Override
        public String valueOf(Variable variable) {
            return variable.getName();
        }

        @Override
        public boolean isCellEditable(Variable variable) {
            return true;
        }

        @Override
        public void setValue(Variable variable, String s) {
            if (s.equals(valueOf(variable))) {
                return;
            }
            variable.setName(s);
            setModified();
        }

        @Override
        protected String getDescription(Variable variable) {
            return variable.getDescription();
        }

        @NotNull
        @Override
        public TableCellEditor getEditor(Variable variable) {
            return new DefaultCellEditor(new JTextField());
        }
    }

    protected class ValueColumnInfo extends ElementsColumnInfoBase<Variable> {
        public ValueColumnInfo() {
            super("Value");
        }

        @Override
        public String valueOf(Variable variable) {
            return variable.getValue();
        }

        @Override
        public boolean isCellEditable(Variable variable) {
            return true;
        }

        @Override
        public void setValue(Variable variable, String s) {
            if (s.equals(valueOf(variable))) {
                return;
            }
            variable.setValue(s);
            setModified();
        }

        @Nullable
        @Override
        protected String getDescription(Variable variable) {
            return variable.getDescription();
        }

        @NotNull
        @Override
        public TableCellEditor getEditor(Variable variable) {
            return new StringWithNewLinesCellEditor();
        }
    }

    private final class CopyPasteProviderPanel extends JPanel implements DataProvider, CopyProvider, PasteProvider {
        private CopyPasteProviderPanel(JComponent component) {
            super(new GridLayout(1, 1));
            add(component);
        }

        @Nullable
        @Override
        public Object getData(@NotNull String dataId) {
            if (PlatformDataKeys.COPY_PROVIDER.is(dataId) || PlatformDataKeys.PASTE_PROVIDER.is(dataId)) {
                return this;
            }
            return null;
        }

        @Override
        public void performCopy(@NotNull DataContext dataContext) {
            TableView<Variable> view = getTableView();
            if (view.isEditing()) {
                int row = view.getEditingRow();
                int column = view.getEditingColumn();
                if (row < 0 || column < 0) {
                    row = view.getSelectedRow();
                    column = view.getSelectedColumn();
                }
                if (row >= 0 && column >= 0) {
                    JTextField textField = (JTextField) ((DefaultCellEditor) view.getCellEditor()).getComponent();
                    CopyPasteManager.getInstance().setContents(new StringSelection(textField.getSelectedText()));
                }
                return;
            }
            stopEditing();
            StringBuilder sb = new StringBuilder();
            List<Variable> variables = getSelection();
            for (Variable variable : variables) {
                if (isEmpty(variable)) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(StringUtil.escapeChars(variable.getName(), '=', ';')).append('=')
                        .append(StringUtil.escapeChars(variable.getValue(), '=', ';'));
            }
            CopyPasteManager.getInstance().setContents(new StringSelection(sb.toString()));
        }


        @Override
        public boolean isCopyEnabled(@NotNull DataContext dataContext) {
            return !getSelection().isEmpty();
        }

        @Override
        public boolean isCopyVisible(@NotNull DataContext dataContext) {
            return isCopyEnabled(dataContext);
        }

        @Override
        public void performPaste(@NotNull DataContext dataContext) {
            String content = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            if (StringUtil.isEmpty(content)) {
                return;
            }
            Map<String, String> map = parseVarssFromText(content);
            TableView<Variable> view = getTableView();
            if ((view.isEditing() || map.isEmpty())) {
                int row = view.getEditingRow();
                int column = view.getEditingColumn();
                if (row < 0 || column < 0) {
                    row = view.getSelectedRow();
                    column = view.getSelectedColumn();
                }
                if (row >= 0 && column >= 0) {
                    TableCellEditor editor = view.getCellEditor();
                    if (editor != null) {
                        Component component = ((DefaultCellEditor) editor).getComponent();
                        if (component instanceof JTextField) {
                            ((JTextField) component).paste();
                        }
                    }
                }
                return;
            }
            stopEditing();
            List<Variable> parsed = new ArrayList<>();
            for (Map.Entry<String, String> entry : map.entrySet()) {
                parsed.add(new Variable(entry.getKey(), entry.getValue()));
            }
            List<Variable> variables = new ArrayList<>(getVariables());
            variables.addAll(parsed);
            variables = ContainerUtil.filter(variables, variable -> !StringUtil.isEmpty(variable.getName()) ||
                    !StringUtil.isEmpty(variable.getValue()));
            setValues(variables);
        }

        @Override
        public boolean isPastePossible(@NotNull DataContext dataContext) {
            return myPasteEnabled;
        }

        @Override
        public boolean isPasteEnabled(@NotNull DataContext dataContext) {
            return myPasteEnabled;
        }
    }

    @Override
    protected AnActionButton @NotNull [] createExtraActions() {
        AnActionButton copyButton = new AnActionButton("Copy", AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                myPanel.performCopy(e.getDataContext());
            }

            @Override
            public boolean isEnabled() {
                return myPanel.isCopyEnabled(DataContext.EMPTY_CONTEXT);
            }
        };
        AnActionButton pasteButton = new AnActionButton("Paste", AllIcons.Actions.MenuPaste) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                myPanel.performPaste(e.getDataContext());
            }

            @Override
            public boolean isEnabled() {
                return myPanel.isPasteEnabled(DataContext.EMPTY_CONTEXT);
            }

            @Override
            public boolean isVisible() {
                return myPanel.isPastePossible(DataContext.EMPTY_CONTEXT);
            }
        };
        return new AnActionButton[]{copyButton, pasteButton};
    }

    @NotNull
    public static Map<String, String> parseVarssFromText(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (content != null && content.contains("=")) {
            boolean legacyFormat = content.contains("\n");
            List<String> pairs;
            if (legacyFormat) {
                pairs = StringUtil.split(content, "\n");
            } else {
                pairs = new ArrayList<>();
                int start = 0;
                int end;
                for (end = content.indexOf(";"); end < content.length(); end = content.indexOf(";", end + 1)) {
                    if (end == -1) {
                        pairs.add(content.substring(start).replace("\\;", ";"));
                        break;
                    }
                    if (end > 0 && (content.charAt(end - 1) != '\\')) {
                        pairs.add(content.substring(start, end).replace("\\;", ";"));
                        start = end + 1;
                    }
                }
            }
            for (String pair : pairs) {
                int pos = pair.indexOf('=');
                if (pos <= 0) continue;
                while (pos > 0 && pair.charAt(pos - 1) == '\\') {
                    pos = pair.indexOf('=', pos + 1);
                }
                pair = pair.replaceAll("[\\\\]", "\\\\\\\\");
                result.put(StringUtil.unescapeStringCharacters(pair.substring(0, pos)).trim(),
                        StringUtil.unescapeStringCharacters(pair.substring(pos + 1)));
            }
        }
        return result;
    }
}
