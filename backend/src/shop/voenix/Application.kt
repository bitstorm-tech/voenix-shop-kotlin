package shop.voenix

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import shop.voenix.auth.AuthRoutes
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings
import shop.voenix.payment.MolliePaymentStatusLookup
import shop.voenix.payment.NotConfiguredMolliePaymentStatusLookup
import shop.voenix.payment.PaymentRoutes

fun Application.module(
    paymentStatusLookup: MolliePaymentStatusLookup = NotConfiguredMolliePaymentStatusLookup(),
) {
    val settings = DatabaseSettings.from(environment.config)
    val databaseFactory = DatabaseFactory(settings)
    val database = databaseFactory.connectAndMigrate()
    AuthRoutes.install(this, database)
    PaymentRoutes.install(this, database, paymentStatusLookup)

    monitor.subscribe(ApplicationStopped) {
        databaseFactory.close()
    }
}
