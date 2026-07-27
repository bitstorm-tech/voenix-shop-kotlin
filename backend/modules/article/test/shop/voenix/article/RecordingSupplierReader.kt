package shop.voenix.article

import java.util.concurrent.CopyOnWriteArrayList
import shop.voenix.supplier.SupplierReader
import shop.voenix.supplier.SupplierSummary

/**
 * The supplier capability of the article tests: it answers from a fixed name table and records
 * every set of ids it was asked for.
 *
 * The recording is the point. The mug list must resolve the suppliers of a whole page in **one**
 * call, so what the tests assert is not only the name in the response but the shape of the lookup
 * behind it: one entry with every distinct id, never one entry per row.
 */
internal class RecordingSupplierReader(private val names: Map<Long, String> = emptyMap()) :
    SupplierReader {
    /** The id sets this reader was asked for, in order. */
    val requestedIds: MutableList<Set<Long>> = CopyOnWriteArrayList()

    override suspend fun find(ids: Set<Long>): Map<Long, SupplierSummary> {
        requestedIds += ids
        return ids.mapNotNull { id -> names[id]?.let { name -> id to SupplierSummary(id, name) } }
            .toMap()
    }
}
