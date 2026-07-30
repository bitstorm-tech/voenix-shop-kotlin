package shop.voenix.generator

import shop.voenix.magiccoins.MagicCoinsOwner

/**
 * The one operation of this module, free of Ktor: generate an image for [MagicCoinsOwner] from what
 * a request carried.
 *
 * The seam exists so the routes can be tested against a stub that records whether it was reached at
 * all — which is how "a request without a CSRF token never generates anything" becomes a provable
 * statement instead of a claim about status codes.
 */
internal fun interface GeneratorOperations {
    suspend fun generate(
        owner: MagicCoinsOwner,
        upload: GenerationUpload,
    ): GenerationOutcome
}
