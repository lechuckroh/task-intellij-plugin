package lechuck.intellij.vars

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.table.JBTable
import com.intellij.util.EnvironmentUtil
import com.intellij.util.ui.ListTableModel
import net.miginfocom.swing.MigLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.table.TableCellRenderer

class VariablesDialog(private val parent: VariablesTextFieldWithBrowseButton) : DialogWrapper(parent, true) {

    private val varTable: VariablesTable
    private val panel: JPanel

    init {
        val varMap = LinkedHashMap(parent.getVars())

        val varList = VariablesTextFieldWithBrowseButton.convertToVariables(varMap)
        varTable = MyVariablesTable(varList)

        val label = JLabel("Variables:")
        label.labelFor = varTable.tableView.component

        panel = JPanel(MigLayout("fill, ins 0, gap 0, hidemode 3")).apply {
            add(label, "hmax pref, wrap")
            add(varTable.component, "push, grow, wrap, gaptop 5")
        }

        title = "Variables"
        init()
    }

    override fun getDimensionServiceKey(): String {
        return "VariablesDialog"
    }

    override fun createCenterPanel(): JComponent {
        return panel
    }

    override fun doValidate(): ValidationInfo? {
        for (variable in varTable.getVariables()) {
            val name = variable.name
            val value = variable.value
            if (name.isEmpty() && value.isEmpty()) {
                continue
            }

            if (!EnvironmentUtil.isValidName(name)) {
                return ValidationInfo("Invalid variable name: $name")
            }
            if (!EnvironmentUtil.isValidValue(value)) {
                return ValidationInfo("Invalid variable value: $name = $value")
            }
        }
        return super.doValidate()
    }

    override fun doOKAction() {
        varTable.stopEditing()
        val vars = LinkedHashMap<String, String>()
        for (variable in varTable.getVariables()) {
            if (variable.name.isEmpty() && variable.value.isEmpty()) {
                continue
            }
            vars[variable.name] = variable.value
        }
        parent.setVars(vars)
        super.doOKAction()
    }

    private class MyVariablesTable(list: List<Variable>) : VariablesTable() {
        init {
            val tableView = tableView
            tableView.visibleRowCount = JBTable.PREFERRED_SCROLLABLE_VIEWPORT_HEIGHT_IN_ROWS
            setValues(list)
            setPasteActionEnabled(true)
        }

        override fun createListModel(): ListTableModel<Variable> {
            return ListTableModel(MyNameColumnInfo(), MyValueColumnInfo())
        }

        inner class MyNameColumnInfo : NameColumnInfo() {
            override fun getCustomizedRenderer(o: Variable?, renderer: TableCellRenderer?): TableCellRenderer? {
                return renderer
            }
        }

        inner class MyValueColumnInfo : ValueColumnInfo() {
            override fun isCellEditable(variable: Variable): Boolean {
                return true
            }

            override fun getCustomizedRenderer(o: Variable?, renderer: TableCellRenderer?): TableCellRenderer? {
                return renderer
            }
        }
    }
}
