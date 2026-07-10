package shop.voenix.country

class CountryRequestException(
    val errors: Map<String, List<String>>,
    cause: Throwable? = null,
) : Exception(errors.toString(), cause)
