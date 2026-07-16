package shop.voenix.email.template

internal object TextEmailLayout {
    fun render(title: String, content: StringBuilder.() -> Unit): String = buildString {
        appendLine(title)
        appendLine(HEADING_SEPARATOR)
        appendLine()
        content()
    }
        .trimEnd()

    fun StringBuilder.separator() {
        appendLine(SEPARATOR)
    }

    private const val HEADING_SEPARATOR = "========================================"
    private const val SEPARATOR = "----------------------------------------"
}
