package shop.voenix.prompt.persistence

/**
 * The stored halves of a composed generation text: the prompt's own [promptText] and the text of
 * every slot variant it is mapped to, already in composition order.
 *
 * Both are the values as they are stored — untrimmed, possibly blank. Trimming and dropping blank
 * parts happens where the text is composed, because that is the rule the module promised: a prompt
 * text is never trimmed on the way in, so the read is the only place that may.
 */
internal data class StoredComposition(
    val promptText: String,
    val variantPrompts: List<String>,
)
