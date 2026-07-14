package shop.voenix.validation

/**
 * Validation messages grouped by lower-camel-case field name.
 *
 * An empty map means the input is valid.
 */
typealias ValidationErrors = Map<String, List<String>>
