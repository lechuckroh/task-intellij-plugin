package lechuck.intellij.vars

import com.intellij.openapi.util.NlsContexts
import org.jetbrains.annotations.NonNls

class Variable(@NonNls var name: String, @NonNls var value: String) {
    fun getDescription(): @NlsContexts.Tooltip String? = null
}
