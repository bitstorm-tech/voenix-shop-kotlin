import type { Transaction } from 'kysely'
import type { Tables } from '@/platform/db/database'
import './schema'

/**
 * The position rules every sequence of this module shares. A writer that decides a position first
 * locks the anchor of its sequence, and only the statements after the lock read the positions it
 * decides from: under `READ COMMITTED` each of them sees what the previous lock holder committed.
 */

/**
 * Locks the anchor row of a global sequence in `prompt_ordering` until the transaction ends. The
 * global anchor is always taken before any category row.
 */
export async function lockSequence(
  trx: Transaction<Tables>,
  sequence: 'SLOT' | 'CATEGORY' | 'PROMPT',
): Promise<void> {
  await trx
    .selectFrom('prompt_ordering')
    .select('sequence')
    .where('sequence', '=', sequence)
    .forUpdate()
    .executeTakeFirstOrThrow()
}

/**
 * Locks the category rows, distinct and ascending by id, one statement each, and answers whether
 * every one exists. A category row is the anchor of its subcategory positions, and while it is
 * held the category cannot disappear. Every writer of more than one category row goes through
 * here: two writers that locked rows in the order they happen to need them could deadlock.
 */
export async function lockCategories(
  trx: Transaction<Tables>,
  categoryIds: Iterable<number>,
): Promise<boolean> {
  for (const id of [...new Set(categoryIds)].sort((a, b) => a - b)) {
    const row = await trx
      .selectFrom('prompt_categories')
      .select('id')
      .where('id', '=', id)
      .forUpdate()
      .executeTakeFirst()
    if (row === undefined) return false
  }
  return true
}

interface Positioned {
  id: number
  position: number
}

/**
 * `ordered` with the row `sourceId` moved to the index `targetId` holds, or `'notFound'` when the
 * stored order contains either id not, or `'conflict'` when the stored positions are not `1..n`.
 * A gap can only come from a writer that ignored the anchor; rewriting it would move every row a
 * client sees, so the move is refused and the evidence stays in place.
 */
export function moved<T extends Positioned>(
  ordered: readonly T[],
  sourceId: number,
  targetId: number,
): T[] | 'notFound' | 'conflict' {
  const sourceIndex = ordered.findIndex((row) => row.id === sourceId)
  const targetIndex = ordered.findIndex((row) => row.id === targetId)
  if (sourceIndex < 0 || targetIndex < 0) return 'notFound'
  if (!ordered.every((row, index) => row.position === index + 1)) return 'conflict'
  const result = [...ordered]
  result.splice(targetIndex, 0, ...result.splice(sourceIndex, 1))
  return result
}

/**
 * Numbers `ordered` from 1 without gaps, writes each row whose stored position differs through
 * `setPosition`, and answers the rows carrying their new positions. The rewrite is single-phase:
 * two rows share a position while it runs, which the deferred unique rule allows until `COMMIT`.
 * The caller holds the anchor of the sequence and has locked the rows.
 */
export async function rewritePositions<T extends Positioned>(
  ordered: readonly T[],
  setPosition: (id: number, position: number) => Promise<unknown>,
): Promise<T[]> {
  const result: T[] = []
  for (const [index, row] of ordered.entries()) {
    const position = index + 1
    if (row.position !== position) await setPosition(row.id, position)
    result.push({ ...row, position })
  }
  return result
}
