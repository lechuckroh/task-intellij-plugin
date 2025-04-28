package lechuck.intellij.domain

data class Include(
    val taskfile: Taskfile,
    val flatten: Boolean = false,
    val internal: Boolean = false,
    val aliases: List<String> = emptyList(),
)