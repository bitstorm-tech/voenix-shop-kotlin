package shop.voenix.prompt.persistence

/**
 * Whether the stored order this list represents is `1..n` without a gap, reading each row's stored
 * place through [position].
 *
 * The ordered lists of this module are dense by construction: creates append behind the last place,
 * deletes close the gap they leave, and reorders rewrite the whole sequence — all of them under the
 * ordering lock of their sequence. Only a writer that ignored that lock, a manual database fix for
 * instance, can leave a gap behind.
 *
 * A reorder is the write that would spread such a gap, because it rewrites positions from a list: a
 * broken sequence would come back repaired and every row a client sees would have moved although it
 * asked to move one. Refusing the move with a retryable conflict instead leaves the evidence in
 * place, which is what the legacy backend did as well.
 *
 * Slot positions are the one sequence that never asks this question: they are gapped by design.
 */
internal fun <T> List<T>.isDenseBy(position: (T) -> Int): Boolean =
    withIndex().all { (index, element) -> position(element) == index + 1 }
