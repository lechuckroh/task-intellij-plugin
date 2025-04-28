package lechuck.intellij.domain

data class Task(
    val label: String = "",
    val desc: String = "",
    val summary: String = "",
    val aliases: List<String> = emptyList(),
    val interactive: Boolean = false,
    val internal: Boolean = false,
    val platforms: List<String> = emptyList(),
)
