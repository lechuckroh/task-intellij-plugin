package lechuck.intellij.vars;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.table.TableView;
import com.intellij.util.EnvironmentUtil;
import com.intellij.util.ui.ListTableModel;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class VariablesDialog extends DialogWrapper {
    private final VariablesTextFieldWithBrowseButton myParent;
    private final VariablesTable myVarTable;
    private final JPanel myPanel;

    protected VariablesDialog(VariablesTextFieldWithBrowseButton parent) {
        super(parent, true);
        myParent = parent;
        Map<String, String> varMap = new LinkedHashMap<>(myParent.getVars());

        List<Variable> varList = VariablesTextFieldWithBrowseButton.convertToVariables(varMap);
        myVarTable = new MyVariablesTable(varList);

        JLabel label = new JLabel("Variables:");
        label.setLabelFor(myVarTable.getTableView().getComponent());

        myPanel = new JPanel(new MigLayout("fill, ins 0, gap 0, hidemode 3"));
        myPanel.add(label, "hmax pref, wrap");
        myPanel.add(myVarTable.getComponent(), "push, grow, wrap, gaptop 5");

        setTitle("Variables");
        init();
    }

    @Nullable
    @Override
    protected String getDimensionServiceKey() {
        return "VariablesDialog";
    }

    @NotNull
    @Override
    protected JComponent createCenterPanel() {
        return myPanel;
    }

    @Nullable
    @Override
    protected ValidationInfo doValidate() {
        for (Variable variable : myVarTable.getVariables()) {
            String name = variable.getName();
            String value = variable.getValue();
            if (StringUtil.isEmpty(name) && StringUtil.isEmpty(value)) {
                continue;
            }

            if (!EnvironmentUtil.isValidName(name)) {
                return new ValidationInfo("Invalid variable name: " + name);
            }
            if (!EnvironmentUtil.isValidValue(value)) {
                return new ValidationInfo("Invalid variable value: " + name + " = " + value);
            }
        }
        return super.doValidate();
    }

    @Override
    protected void doOKAction() {
        myVarTable.stopEditing();
        final Map<String, String> vars = new LinkedHashMap<>();
        for (Variable variable : myVarTable.getVariables()) {
            if (StringUtil.isEmpty(variable.getName()) && StringUtil.isEmpty(variable.getValue())) {
                continue;
            }
            vars.put(variable.getName(), variable.getValue());
        }
        myParent.setVars(vars);
        super.doOKAction();
    }

    private static class MyVariablesTable extends VariablesTable {
        MyVariablesTable(List<Variable> list) {
            TableView<Variable> tableView = getTableView();
            tableView.setVisibleRowCount(JBTable.PREFERRED_SCROLLABLE_VIEWPORT_HEIGHT_IN_ROWS);
            setValues(list);
            setPasteActionEnabled(true);
        }

        @Override
        protected ListTableModel createListModel() {
            return new ListTableModel(new MyNameColumnInfo(), new MyValueColumnInfo());
        }

        protected class MyNameColumnInfo extends NameColumnInfo {
            @Override
            public TableCellRenderer getCustomizedRenderer(Variable o, TableCellRenderer renderer) {
                return renderer;
            }
        }

        protected class MyValueColumnInfo extends ValueColumnInfo {
            @Override
            public boolean isCellEditable(Variable variable) {
                return true;
            }

            @Override
            public TableCellRenderer getCustomizedRenderer(Variable o, TableCellRenderer renderer) {
                return renderer;
            }
        }
    }
}
