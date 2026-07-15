package shop.voenix.country

public interface CountryReader {
    public suspend fun find(ids: Set<Long>): Map<Long, Country>
}
