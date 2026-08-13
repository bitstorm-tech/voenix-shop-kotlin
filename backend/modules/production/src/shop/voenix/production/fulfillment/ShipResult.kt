package shop.voenix.production.fulfillment

/**
 * What a ship request can end as. The three failures are the three answers the guarded update's "no
 * row touched" can mean, told apart by a re-read inside the same transaction:
 *
 * - [NotFound] — the job does not exist, or it belongs to another supplier. Deliberately one
 *   answer: telling a supplier that a foreign job exists is already too much.
 * - [AlreadyShipped] — somebody (or a second click) shipped it first. The first shipment stands and
 *   no second mail goes out.
 * - [NotReady] — the job has no generated artifact yet, so there is nothing to have packed
 *   (decision J1 of issue #119).
 *
 * [Shipped] carries the updated view of the surface that asked, which is why the type is generic: a
 * supplier gets a `SupplierJobView`, an admin an `AdminJobView`, and both come from the one service
 * path that performs the write.
 */
internal sealed interface ShipResult<out V> {
    data class Shipped<out V>(val job: V) : ShipResult<V>

    data object NotFound : ShipResult<Nothing>

    data object AlreadyShipped : ShipResult<Nothing>

    data object NotReady : ShipResult<Nothing>
}
