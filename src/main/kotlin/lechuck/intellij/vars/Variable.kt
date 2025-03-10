package lechuck.intellij.vars

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.util.NlsContexts
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.NotNull

class Variable(@NonNls var name: String, @NonNls var value: String) : PersistentStateComponent<Variable> {

    fun getDescription(): @NlsContexts.Tooltip String? {
        return null
    }

    override fun getState(): Variable {
        return this
    }

    override fun loadState(state: @NotNull Variable) {
        this.name = state.name
        this.value = state.value
    }
}
