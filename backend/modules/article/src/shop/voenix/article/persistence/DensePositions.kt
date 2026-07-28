package shop.voenix.article.persistence

/**
 * Whether the stored order this list represents is `1..n` without a gap, reading each row's stored
 * place through [position].
 *
 * Every ordered list in this module is dense by construction: creates append behind the last place,
 * deletes close the gap they leave, and reorders rewrite the whole sequence — all of them under the
 * ordering lock of their sequence. Only a writer that ignored that lock, a manual database fix for
 * instance, can leave a gap behind.
 *
 * A reorder is the write that would spread such a gap, because it rewrites positions from a list: a
 * broken sequence would come back repaired and every row a client sees would have moved although it
 * asked to move one. Refusing the move with a retryable conflict instead is what the legacy backend
 * did before its own rewrite, and it leaves the evidence in place. The three reorders of this
 * module — mugs, categories, subcategories — therefore ask this one question with this one
 * implementation.
 */
internal fun <T> List<T>.isDenseBy(position: (T) -> Int): Boolean =
    withIndex().all { (index, element) -> position(element) == index + 1 }
