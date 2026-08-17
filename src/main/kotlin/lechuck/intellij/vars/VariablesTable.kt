package lechuck.intellij.vars

import com.intellij.execution.ExecutionBundle
import com.intellij.execution.util.ListTableWithButtons
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
        copyAction?.registerCustomShortcutSet(
            copyAction.shortcutSet,
            tableView,
        ) // no need to add in popup menu
        val pasteAction = ActionManager.getInstance().getAction(IdeActions.ACTION_PASTE)
        pasteAction?.registerCustomShortcutSet(
            pasteAction.shortcutSet,
            tableView,
        ) // no need to add in popup menu
    }

    fun setPasteActionEnabled(enabled: Boolean) {
        myPasteEnabled = enabled
    }

    override fun createListModel(): ListTableModel<Variable> {
        return ListTableModel(NameColumnInfo(), ValueColumnInfo())
    }

    fun editVariableName(variable: Variable) {
        ApplicationManager.getApplication().invokeLater {
            val actualVar =
                ContainerUtil.find(getElements()) { item ->
                    StringUtil.equals(variable.name, item.name)
                }
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
            return createCellEditor()
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
            return createCellEditor()
        }
    }

    private inner class CopyPasteProviderPanel(component: JComponent) :
        JPanel(GridLayout(1, 1)), DataProvider, CopyProvider, PasteProvider {

        init {
            add(component)
        }

        override fun getData(dataId: String): Any? {
            return if (
                PlatformDataKeys.COPY_PROVIDER.`is`(dataId) ||
                    PlatformDataKeys.PASTE_PROVIDER.`is`(dataId)
            ) {
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
                    CopyPasteManager.getInstance()
                        .setContents(StringSelection(textField.selectedText))
                }
                return
            }
            stopEditing()
            val text = stringifyForCopy(selection.filterNot { isEmpty(it) })
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        }

        override fun isCopyEnabled(dataContext: DataContext): Boolean {
            return selection.isNotEmpty()
        }

        override fun isCopyVisible(dataContext: DataContext): Boolean {
            return isCopyEnabled(dataContext)
        }

        override fun performPaste(dataContext: DataContext) {
            val content =
                CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
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
            variables =
                ArrayList(
                    ContainerUtil.filter(variables) { variable ->
                        !StringUtil.isEmpty(variable.name) || !StringUtil.isEmpty(variable.value)
                    }
                )
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
        val copyButton =
            object : AnActionButton("Copy", AllIcons.Actions.Copy) {
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
        val pasteButton =
            object : AnActionButton("Paste", AllIcons.Actions.MenuPaste) {
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
        /**
         * Characters a backslash may escape. A backslash before anything else is a literal
         * character, which is what lets a Windows path like `C:\temp\new` be typed as-is.
         *
         * The backslash itself must be in this set. Without it the encoding is not reversible, and
         * a value ending in a backslash would swallow the following variable.
         *
         * The cost is that text from elsewhere is read by these rules too, so a pasted `\\server`
         * arrives as `\server`. Reversibility for what this table writes is worth more.
         */
        private const val ESCAPABLE = "\\;="

        /** Both columns use a plain text field, whose document filters newlines. */
        internal fun createCellEditor(): TableCellEditor = DefaultCellEditor(JTextField())

        /**
         * Reads the `name=value;name=value` form written by [stringifyForCopy] and by
         * `VariablesTextFieldWithBrowseButton.stringifyVars`.
         */
        fun parseVarsFromText(content: String?): Map<String, String> {
            val result: MutableMap<String, String> = LinkedHashMap()
            if (content == null || !content.contains("=")) {
                return result
            }

            for (pair in splitOnSeparators(content)) {
                val pos = indexOfUnescaped(pair, '=')
                // no separator, or nothing before it: there is no pair to add
                if (pos <= 0) continue
                result[unescape(pair.substring(0, pos)).trim()] = unescape(pair.substring(pos + 1))
            }
            return result
        }

        /**
         * Renders variables for the clipboard. Unlike the text field writer, this escapes '=' too,
         * so a name containing one survives the round trip.
         */
        internal fun stringifyForCopy(vars: List<Variable>): String {
            val buf = StringBuilder()
            for (variable in vars) {
                if (buf.isNotEmpty()) {
                    buf.append(';')
                }
                buf.append(escape(variable.name, '=', ';'))
                    .append('=')
                    .append(escape(variable.value, '=', ';'))
            }
            return buf.toString()
        }

        /** Escapes [chars] so that [parseVarsFromText] reads the string back unchanged. */
        internal fun escape(s: String, vararg chars: Char): String {
            // escaping anything else would not survive the round trip
            require(chars.all { it in ESCAPABLE }) { "not escapable: ${chars.toList()}" }
            val buf = StringBuilder(s.length)
            for (c in s) {
                // the backslash is always escaped, otherwise the encoding is ambiguous
                if (c == '\\' || chars.contains(c)) {
                    buf.append('\\')
                }
                buf.append(c)
            }
            return buf.toString()
        }

        private fun unescape(s: String): String {
            val buf = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                    buf.append(s[i + 1])
                    i += 2
                } else {
                    buf.append(c)
                    i++
                }
            }
            return buf.toString()
        }

        /** Index of the first [target] not preceded by a backslash, or -1. */
        private fun indexOfUnescaped(s: String, target: Char, from: Int = 0): Int {
            var i = from
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                    i += 2
                    continue
                }
                if (c == target) {
                    return i
                }
                i++
            }
            return -1
        }

        /** Splits on ';', leaving an escaped one inside the value it belongs to. */
        private fun splitOnSeparators(content: String): List<String> {
            val parts = mutableListOf<String>()
            var start = 0
            while (true) {
                val end = indexOfUnescaped(content, ';', start)
                if (end < 0) {
                    parts.add(content.substring(start))
                    return parts
                }
                parts.add(content.substring(start, end))
                start = end + 1
            }
        }
    }
}
