package shop.voenix

import io.ktor.server.config.ApplicationConfig
import shop.voenix.account.AccountSettings
import shop.voenix.auth.AuthSettings
import shop.voenix.db.DatabaseSettings
import shop.voenix.email.EmailSettings
import shop.voenix.generator.GeneratorSettings
import shop.voenix.image.ImageSettings
import shop.voenix.payment.MollieSettings
import shop.voenix.production.ProductionSettings

/**
 * Every module setting the application reads, read in one place and before anything is installed.
 *
 * Each module owns the rules for its own block — what a value means, which combinations it refuses
 * — and this class only says which blocks exist. Reading them all up front is what makes a
 * misconfigured deployment fail *before* Flyway touches the database, which the application's
 * database test pins.
 */
@Suppress("LongParameterList")
internal class ApplicationSettings(
    val database: DatabaseSettings,
    val auth: AuthSettings,
    val image: ImageSettings,
    val email: EmailSettings,
    val production: ProductionSettings,
    val account: AccountSettings,
    val generator: GeneratorSettings,
    val mollie: MollieSettings,
) {
    companion object {
        /**
         * [mollie] overrides the `Mollie:` block and is how the composition test points the payment
         * module at a local stub; a running deployment never passes it.
         */
        fun from(
            config: ApplicationConfig,
            mollie: MollieSettings? = null,
        ): ApplicationSettings =
            ApplicationSettings(
                database = DatabaseSettings.from(config),
                auth = AuthSettings.from(config),
                image = ImageSettings.from(config),
                email = EmailSettings.from(config),
                production = ProductionSettings.from(config),
                account = AccountSettings.from(config),
                generator = GeneratorSettings.from(config),
                mollie = mollie ?: MollieSettings.from(config),
            )
    }
}
