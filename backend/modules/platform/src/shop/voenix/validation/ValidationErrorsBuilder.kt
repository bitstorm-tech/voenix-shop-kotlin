package shop.voenix.validation

/**
 * Collects validation messages per field.
 *
 * Several rules may reject the same field, so a second message is added to the first rather than
 * replacing it. Fields and their messages keep the order in which the rules ran, and duplicates are
 * kept: this is an accumulator, not a set.
 */
public class ValidationErrorsBuilder {
    private val messagesByField = LinkedHashMap<String, List<String>>()

    /** Adds one more [message] to [field]. */
    public fun add(
        field: String,
        message: String,
    ) {
        addAll(field, listOf(message))
    }

    /** Adds every message of [messages] to [field]. An empty list adds no key. */
    public fun addAll(
        field: String,
        messages: List<String>,
    ) {
        if (messages.isEmpty()) return
        messagesByField.merge(field, messages) { existing, added -> existing + added }
    }

    /**
     * Merges [errors], usually the result of a nested type's own `validate()`, into this builder.
     */
    public fun addAll(errors: ValidationErrors) {
        errors.forEach { (field, messages) -> addAll(field, messages) }
    }

    /** The collected errors so far. The returned map is a snapshot: later adds do not change it. */
    public fun build(): ValidationErrors = messagesByField.toMap()
}

/**
 * The field errors the rules in [build] report; empty when the input is valid.
 *
 * This mirrors `buildMap {}`, which it replaces, and it is `inline` so that suspending calls work
 * inside the block.
 */
public inline fun buildValidationErrors(
    build: ValidationErrorsBuilder.() -> Unit
): ValidationErrors = ValidationErrorsBuilder().apply(build).build()
