package lechuck.intellij.vars

import org.jdom.Element

/**
 * Holds variables configuration:
 */
data class VariablesData(val vars: Map<String, String>) {

    companion object {
        val DEFAULT = VariablesData(emptyMap())

        private const val VARS = "vars"
        private const val VAR = VariablesComponent.VAR
        private const val NAME = VariablesComponent.NAME
        private const val VALUE = VariablesComponent.VALUE

        fun readExternal(element: Element): VariablesData {
            val varsElement = element.getChild(VARS) ?: return DEFAULT
            var vars: MutableMap<String, String> = emptyMap<String, String>().toMutableMap()
            for (varElement in varsElement.getChildren(VAR)) {
                val varName = varElement.getAttributeValue(NAME)
                val varValue = varElement.getAttributeValue(VALUE)
                if (varName != null && varValue != null) {
                    if (vars.isEmpty()) {
                        vars = LinkedHashMap()
                    }
                    vars[varName] = varValue
                }
            }
            return create(vars)
        }

        /**
         * @param vars Map instance containing variables
         *             (iteration order should be reliable user-specified, like {@link LinkedHashMap} or {@link ImmutableMap})
         */
        fun create(vars: Map<String, String>): VariablesData {
            return VariablesData(vars)
        }
    }

    fun writeExternal(parent: Element) {
        val varsElement = Element(VARS)
        for ((key, value) in vars) {
            varsElement.addContent(Element(VAR).setAttribute(NAME, key).setAttribute(VALUE, value))
        }
        parent.addContent(varsElement)
    }

    fun with(vars: Map<String, String>): VariablesData {
        return VariablesData(vars)
    }
}