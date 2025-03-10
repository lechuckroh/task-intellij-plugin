package lechuck.intellij.vars

import com.intellij.icons.AllIcons
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.UserActivityProviderComponent
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.annotations.NotNull
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Icon
import javax.swing.KeyStroke
import javax.swing.event.ChangeEvent
import javax.swing.event.ChangeListener
import javax.swing.event.DocumentEvent

class VariablesTextFieldWithBrowseButton : TextFieldWithBrowseButton(), UserActivityProviderComponent {
    private var myData: VariablesData = VariablesData.DEFAULT
    private val myListeners = ContainerUtil.createLockFreeCopyOnWriteList<ChangeListener>()

    init {
        addActionListener {
            setVars(VariablesTable.parseVarsFromText(text))
            createDialog().show()
        }
        textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(@NotNull e: DocumentEvent) {
                if (!StringUtil.equals(stringifyVars(myData), text)) {
                    val textVars: Map<String, String> = VariablesTable.parseVarsFromText(text)
                    myData = myData.with(textVars)
                    fireStateChanged()
                }
            }
        })
    }

    private fun createDialog(): VariablesDialog {
        return VariablesDialog(this)
    }

    /**
     * @return unmodifiable Map instance
     */
    fun getVars(): Map<String, String> {
        return myData.vars
    }

    /**
     * @param vars Map instance containing variables
     *             (iteration order should be reliable user-specified, like [LinkedHashMap] or [ImmutableMap])
     */
    fun setVars(vars: Map<String, String>) {
        setData(myData.with(vars))
    }

    fun getData(): VariablesData {
        return myData
    }

    fun setData(data: VariablesData) {
        val oldData = myData
        myData = data
        text = stringifyVars(data)
        if (oldData != data) {
            fireStateChanged()
        }
    }

    override fun getDefaultIcon(): Icon {
        return AllIcons.General.InlineVariables
    }

    override fun getHoveredIcon(): Icon {
        return AllIcons.General.InlineVariablesHover
    }

    private fun stringifyVars(varData: VariablesData): String {
        if (varData.vars.isEmpty()) {
            return ""
        }
        val buf = StringBuilder()
        for ((key, value) in varData.vars) {
            if (buf.isNotEmpty()) {
                buf.append(";")
            }
            buf.append(StringUtil.escapeChar(key, ';'))
                .append("=")
                .append(StringUtil.escapeChar(value, ';'))
        }
        return buf.toString()
    }

    override fun addChangeListener(l: ChangeListener) {
        myListeners.add(l)
    }

    override fun removeChangeListener(l: ChangeListener) {
        myListeners.remove(l)
    }

    private fun fireStateChanged() {
        for (listener in myListeners) {
            listener.stateChanged(ChangeEvent(this))
        }
    }


    override fun getIconTooltip(): @NlsContexts.Tooltip String {
        val shortcut = KeymapUtil.getKeystrokeText(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
        )
        return "Edit variables ($shortcut)"
    }

    companion object {
        fun convertToVariables(map: Map<String, String>): List<Variable> {
            return ContainerUtil.map(map.entries) { (key, value) -> Variable(key, value) }
        }
    }
}