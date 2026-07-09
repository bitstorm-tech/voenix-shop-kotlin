package shop.voenix

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import shop.voenix.auth.AuthRoutes
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings

fun Application.module() {
    val settings = DatabaseSettings.from(environment.config)
    val databaseFactory = DatabaseFactory(settings)
    val database = databaseFactory.connectAndMigrate()
    AuthRoutes.install(this, database)

    monitor.subscribe(ApplicationStopped) {
        databaseFactory.close()
    }
}
