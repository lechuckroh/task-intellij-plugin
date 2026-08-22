package lechuck.intellij.vars

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.ListTableModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.table.TableCellRenderer
import net.miginfocom.swing.MigLayout

class VariablesDialog(private val parent: VariablesTextFieldWithBrowseButton) :
    DialogWrapper(parent, true) {

    private val varTable: VariablesTable
    private val panel: JPanel

    init {
        val varMap = LinkedHashMap(parent.getVars())

        val varList = VariablesTextFieldWithBrowseButton.convertToVariables(varMap)
        varTable = MyVariablesTable(varList)

        val label = JLabel("Variables:")
        label.labelFor = varTable.tableView.component

        panel =
            JPanel(MigLayout("fill, ins 0, gap 0, hidemode 3")).apply {
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
        val variables = varTable.getVariables()
        for (variable in variables) {
            val error = validationError(variable.name, variable.value)
            if (error != null) {
                return ValidationInfo(error)
            }
        }
        for (variable in variables) {
            val warning = validationWarning(variable.name)
            if (warning != null) {
                // non-blocking: names like these were accepted before this check existed, so
                // they must not stop an existing configuration from being saved
                return ValidationInfo(warning).asWarning().withOKEnabled()
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

    companion object {
        // what a Taskfile can reference as {{.NAME}}: Go template field syntax, which permits
        // Unicode letters and digits plus '_' (text/template's isAlphaNumeric), not just ASCII
        private val TEMPLATE_REFERENCABLE = Regex("[\\p{L}_][\\p{L}\\p{Nd}_]*")

        /** @return the message to show, or null if the variable is acceptable */
        internal fun validationError(name: String, value: String): String? {
            if (name.isEmpty() && value.isEmpty()) {
                return null
            }
            // These are task variables, not environment variables: they travel as one
            // 'NAME=VALUE' command-line argument that task splits on its first '='. An empty
            // name or a '=' anywhere in one silently yields a differently-named variable --
            // the environment-variable rules used before even allowed a leading '=' on Windows.
            if (name.isEmpty()) {
                return "Variable name cannot be empty"
            }
            if (name.contains('=')) {
                return "Variable name cannot contain '='"
            }
            // exec silently truncates an argument at a NUL byte
            if (name.contains('\u0000') || value.contains('\u0000')) {
                return "Variable cannot contain a NUL character"
            }
            // task interpolates variables into the shell command, so a line break in one ends the
            // command and the rest is run as another: 'task NAME=a<newline>b' fails with exit 127
            if (containsLineBreak(name)) {
                return "Variable name cannot contain a line break"
            }
            if (containsLineBreak(value)) {
                return "Variable value cannot contain a line break: $name"
            }
            return null
        }

        /**
         * @return a non-blocking message for a name that task accepts on the command line but a
         *   Taskfile cannot reference as a template field, or null if the name is fine
         */
        internal fun validationWarning(name: String): String? {
            if (name.isEmpty() || name.matches(TEMPLATE_REFERENCABLE)) {
                return null
            }
            return "'$name' cannot be referenced as {{.$name}} in a Taskfile"
        }

        private fun containsLineBreak(s: String) = s.contains('\n') || s.contains('\r')
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
            override fun getCustomizedRenderer(
                o: Variable?,
                renderer: TableCellRenderer?,
            ): TableCellRenderer? {
                return renderer
            }
        }

        inner class MyValueColumnInfo : ValueColumnInfo() {
            override fun isCellEditable(variable: Variable): Boolean {
                return true
            }

            override fun getCustomizedRenderer(
                o: Variable?,
                renderer: TableCellRenderer?,
            ): TableCellRenderer? {
                return renderer
            }
        }
    }
}
