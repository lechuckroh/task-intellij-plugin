package lechuck.intellij.util

import java.util.regex.Pattern

object RegexUtil {
    fun splitBySpace(str: String?): List<String> {
        if (str == null) {
            return emptyList()
        }
        val matcher = Pattern.compile("([^\"]\\S*|\"[^\"]*\")\\s*").matcher(str)
        val result = ArrayList<String>()

        while (matcher.find()) {
            val item: String = matcher.group(1).replace("\"", "")
            result.add(item)
        }
        return result
    }
}