package shop.voenix.prompt.persistence

/**
 * A stored prompt representation [prompt], plus the id of the price row the prompt owns.
 *
 * The price id is deliberately *next to* the representation instead of inside it: no prompt
 * contract carries a price id, and the amounts themselves are calculated by the pricing module,
 * which this repository does not call while it reads. Persistence therefore answers with the
 * reference and the service turns it into the embedded price — with **one** batched
 * `PriceCatalog.find` per response, for a detail as well as for a whole list.
 *
 * That is also why the type is generic: the detail and the list row are two different
 * representations of a prompt, but "the row I read, and the price row it points at" is one idea and
 * deserves one type.
 */
internal data class StoredPrompt<out T>(
    val prompt: T,
    val priceId: Long?,
)
