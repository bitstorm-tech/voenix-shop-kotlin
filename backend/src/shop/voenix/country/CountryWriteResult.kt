package shop.voenix.country

internal sealed interface CountryWriteResult {
    data class Stored(val country: Country) : CountryWriteResult

    data object NotFound : CountryWriteResult

    data object Conflict : CountryWriteResult
}
