package shop.voenix.vat

public interface VatReader {
    public suspend fun list(): List<Vat>

    public suspend fun find(ids: Set<Long>): Map<Long, Vat>
}
