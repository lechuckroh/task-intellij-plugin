package lechuck.intellij.vars

import com.intellij.execution.ExecutionBundle
import com.intellij.execution.util.ListTableWithButtons
import com.intellij.execution.util.StringWithNewLinesCellEditor
import com.intellij.icons.AllIcons
import com.intellij.ide.CopyProvider
import com.intellij.ide.PasteProvider
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnActionButton
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.ui.ListTableModel
import java.awt.GridLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.table.TableCellEditor

open class VariablesTable : ListTableWithButtons<Variable>() {
    private var myPanel: CopyPasteProviderPanel? = null
    private var myPasteEnabled = false

    init {
        tableView.emptyText.text = ExecutionBundle.message("empty.text.no.variables")
        val copyAction = ActionManager.getInstance().getAction(IdeActions.ACTION_COPY)
        copyAction?.registerCustomShortcutSet(copyAction.shortcutSet, tableView) // no need to add in popup menu
        val pasteAction = ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE)
        pasteAction?.registerCustomShortcutSet(pasteAction.shortcutSet, tableView) // no need to add in popup menu
    }

    fun setPasteActionEnabled(enabled: Boolean) {
        myPasteEnabled = enabled
    }

    override fun createListModel(): ListTableModel<Variable> {
        return ListTableModel(NameColumnInfo(), ValueColumnInfo())
    }

    fun editVariableName(variable: Variable) {
        ApplicationManager.getApplication().invokeLater {
            val actualVar = ContainerUtil.find(getElements()) { item -> StringUtil.equals(variable.name, item.name) }
            if (actualVar == null) {
                return@invokeLater
            }

            setSelection(actualVar)
            editSelection(0)
        }
    }

    fun getVariables(): List<Variable> {
        return getElements()
    }

    override fun getComponent(): JComponent {
        if (myPanel == null) {
            myPanel = CopyPasteProviderPanel(super.getComponent())
        }
        return myPanel as CopyPasteProviderPanel
    }

    override fun createElement(): Variable {
        return Variable("", "")
    }

    override fun isEmpty(element: Variable): Boolean {
        return element.name.isEmpty() && element.value.isEmpty()
    }

    override fun cloneElement(variable: Variable): Variable {
        return Variable(variable.name, variable.value)
    }

    override fun canDeleteElement(selection: Variable): Boolean {
        return true
    }

    protected open inner class NameColumnInfo : ElementsColumnInfoBase<Variable>("Name") {
        override fun valueOf(variable: Variable): String {
            return variable.name
        }

        override fun isCellEditable(variable: Variable): Boolean {
            return true
        }

        override fun setValue(variable: Variable, s: String) {
            if (s == valueOf(variable)) {
                return
            }
            variable.name = s
            setModified()
        }

        override fun getDescription(variable: Variable): String? {
            return variable.getDescription()
        }

        override fun getEditor(variable: Variable): TableCellEditor {
            return DefaultCellEditor(JTextField())
        }
    }

    protected open inner class ValueColumnInfo : ElementsColumnInfoBase<Variable>("Value") {
        override fun valueOf(variable: Variable): String {
            return variable.value
        }

        override fun isCellEditable(variable: Variable): Boolean {
            return true
        }

        override fun setValue(variable: Variable, s: String) {
            if (s == valueOf(variable)) {
                return
            }
            variable.value = s
            setModified()
        }

        override fun getDescription(variable: Variable): String? {
            return variable.getDescription()
        }

        override fun getEditor(variable: Variable): TableCellEditor {
            return StringWithNewLinesCellEditor()
        }
    }

    private inner class CopyPasteProviderPanel(component: JComponent) : JPanel(GridLayout(1, 1)), DataProvider,
        CopyProvider, PasteProvider {

        init {
            add(component)
        }

        override fun getData(dataId: String): Any? {
            return if (PlatformDataKeys.COPY_PROVIDER.`is`(dataId) || PlatformDataKeys.PASTE_PROVIDER.`is`(dataId)) {
                this
            } else null
        }

        override fun performCopy(dataContext: DataContext) {
            val view = tableView
            if (view.isEditing) {
                var row = view.editingRow
                var column = view.editingColumn
                if (row < 0 || column < 0) {
                    row = view.selectedRow
                    column = view.selectedColumn
                }
                if (row >= 0 && column >= 0) {
                    val textField = (view.cellEditor as DefaultCellEditor).component as JTextField
                    CopyPasteManager.getInstance().setContents(StringSelection(textField.selectedText))
                }
                return
            }
            stopEditing()
            val sb = StringBuilder()
            val variables = selection
            for (variable in variables) {
                if (isEmpty(variable)) continue
                if (sb.isNotEmpty()) sb.append(';')
                sb.append(StringUtil.escapeChars(variable.name, '=', ';')).append('=')
                    .append(StringUtil.escapeChars(variable.value, '=', ';'))
            }
            CopyPasteManager.getInstance().setContents(StringSelection(sb.toString()))
        }

        override fun isCopyEnabled(dataContext: DataContext): Boolean {
            return selection.isNotEmpty()
        }

        override fun isCopyVisible(dataContext: DataContext): Boolean {
            return isCopyEnabled(dataContext)
        }

        override fun performPaste(dataContext: DataContext) {
            val content = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
            if (StringUtil.isEmpty(content)) {
                return
            }
            val map = parseVarsFromText(content)
            val view = tableView
            if (view.isEditing || map.isEmpty()) {
                var row = view.editingRow
                var column = view.editingColumn
                if (row < 0 || column < 0) {
                    row = view.selectedRow
                    column = view.selectedColumn
                }
                if (row >= 0 && column >= 0) {
                    val editor = view.cellEditor
                    if (editor != null) {
                        val component = (editor as DefaultCellEditor).component
                        if (component is JTextField) {
                            component.paste()
                        }
                    }
                }
                return
            }
            stopEditing()
            val parsed = ArrayList<Variable>()
            for ((key, value) in map) {
                parsed.add(Variable(key, value))
            }
            var variables = ArrayList(getVariables())
            variables.addAll(parsed)
            variables = ArrayList(ContainerUtil.filter(variables) { variable ->
                !StringUtil.isEmpty(variable.name) || !StringUtil.isEmpty(variable.value)
            })
            setValues(variables)
        }

        override fun isPastePossible(dataContext: DataContext): Boolean {
            return myPasteEnabled
        }

        override fun isPasteEnabled(dataContext: DataContext): Boolean {
            return myPasteEnabled
        }
    }

    override fun createExtraToolbarActions(): Array<AnAction> {
        val copyButton = object : AnActionButton("Copy", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                myPanel?.performCopy(e.dataContext)
            }

            override fun isEnabled(): Boolean {
                return myPanel?.isCopyEnabled(DataContext.EMPTY_CONTEXT) == true
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        val pasteButton = object : AnActionButton("Paste", AllIcons.Actions.MenuPaste) {
            override fun actionPerformed(e: AnActionEvent) {
                myPanel?.performPaste(e.dataContext)
            }

            override fun isEnabled(): Boolean {
                return myPanel?.isPasteEnabled(DataContext.EMPTY_CONTEXT) == true
            }

            override fun isVisible(): Boolean {
                return myPanel?.isPastePossible(DataContext.EMPTY_CONTEXT) == true
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }
        return arrayOf(copyButton, pasteButton)
    }

    companion object {
        fun parseVarsFromText(content: String?): Map<String, String> {
            val result: MutableMap<String, String> = LinkedHashMap()
            if (content != null && content.contains("=")) {
                val pairs = mutableListOf<String>()
                var start = 0
                var end: Int
                while (true) {
                    end = content.indexOf(";", start)
                    if (end == -1 || start >= content.length) {
                        if (start < content.length) {
                            pairs.add(content.substring(start).replace("\\;", ";"))
                        }
                        break
                    }
                    pairs.add(content.substring(start, end).replace("\\;", ";"))
                    start = end + 1
                }
                for (pair in pairs) {
                    var pos = pair.indexOf('=')
                    if (pos <= 0) continue
                    while (pos > 0 && pair[pos - 1] == '\\') {
                        pos = pair.indexOf('=', pos + 1)
                    }
                    val key = StringUtil.unescapeStringCharacters(pair.substring(0, pos)).trim()
                    val value = StringUtil.unescapeStringCharacters(pair.substring(pos + 1))
                    result[key] = value
                }
            }
            return result
        }
    }
}