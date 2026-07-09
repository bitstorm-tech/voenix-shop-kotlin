package shop.voenix.payment

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

object PaymentRoutes {
    const val MOLLIE_WEBHOOK_PATH = "/payments/mollie/webhook"

    fun install(
        application: Application,
        database: Database,
        paymentStatusLookup: MolliePaymentStatusLookup,
    ) {
        val repository = PaymentProofRepository(database)

        application.routing {
            post(MOLLIE_WEBHOOK_PATH) {
                val paymentId = call.receiveParameters()["id"]?.trim().orEmpty()
                if (paymentId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }

                val payment =
                    try {
                        paymentStatusLookup.fetch(paymentId)
                    } catch (error: Exception) {
                        if (error is CancellationException) {
                            throw error
                        }

                        call.respond(HttpStatusCode.ServiceUnavailable)
                        return@post
                    }

                if (payment != null) {
                    repository.markOrderPaidFromMollie(payment, nowEpochSeconds())
                }

                call.respondText("ok")
            }
        }
    }

    private fun nowEpochSeconds(): Long = Instant.now().epochSecond
}
