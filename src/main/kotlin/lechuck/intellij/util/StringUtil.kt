package lechuck.intellij.util

object StringUtil {
    fun splitVars(str: String): Map<String, String> {
        val varMap = mutableMapOf<String, String>()

        str.split(";").forEach { kvStr ->
            val idx = kvStr.indexOf("=")
            if (idx > -1) {
                val key = kvStr.substring(0, idx)
                val value = if (idx < kvStr.length - 1) kvStr.substring(idx + 1) else ""
                varMap[key] = value
            }
        }

        return varMap.toMap()
    }
}
