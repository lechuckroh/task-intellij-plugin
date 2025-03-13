package lechuck.intellij.vars

import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.UserActivityProviderComponent
import javax.swing.event.ChangeListener
import org.jetbrains.annotations.NonNls

class VariablesComponent :
    LabeledComponent<TextFieldWithBrowseButton>(), UserActivityProviderComponent {

    @NonNls
    companion object {
        const val VAR = "var"
        const val NAME = "name"
        const val VALUE = "value"
    }

    private val comp: VariablesTextFieldWithBrowseButton

    init {
        comp = createBrowseComponent()
        this.component = comp
        text = "Variables"
    }

    var varData: VariablesData
        get() = comp.getData()
        set(value) {
            comp.setData(value)
        }

    private fun createBrowseComponent(): VariablesTextFieldWithBrowseButton {
        return VariablesTextFieldWithBrowseButton()
    }

    override fun addChangeListener(l: ChangeListener) {
        comp.addChangeListener(l)
    }

    override fun removeChangeListener(l: ChangeListener) {
        comp.removeChangeListener(l)
    }
}
