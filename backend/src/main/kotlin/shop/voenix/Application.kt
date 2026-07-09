package shop.voenix

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import shop.voenix.db.DatabaseFactory
import shop.voenix.db.DatabaseSettings

fun Application.module() {
    val settings = DatabaseSettings.from(environment.config)
    val databaseFactory = DatabaseFactory(settings)
    databaseFactory.connectAndMigrate()

    monitor.subscribe(ApplicationStopped) {
        databaseFactory.close()
    }
}
