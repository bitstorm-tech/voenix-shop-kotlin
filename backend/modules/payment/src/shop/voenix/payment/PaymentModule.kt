package shop.voenix.payment

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.order.OrderPaymentGateway

/**
 * The runtime handle of the installed payment module.
 *
 * It is public only because the composition root has to name the type it gets back; the module
 * exports no capability yet. Everything behind it — the service, the repository, the table, the
 * Mollie adapter — stays internal, and the one thing the outside world can reach is the webhook
 * route.
 *
 * The handle owns one piece of lifecycle: the Mollie adapter's HTTP client, closed when the
 * application stops.
 */
public class PaymentModule internal constructor(internal val operations: PaymentOperations)

/**
 * Assembles the payment module.
 *
 * [orders] is the entire outside world a payment needs — the two writes the order module handed it
 * — and [mollie] is the provider. Nothing else is injected, and in particular no order *reader*:
 * the module never looks an order up, it is told what to charge for.
 */
internal fun createPaymentModule(
    database: Database,
    mollie: MolliePayments,
    orders: OrderPaymentGateway,
): PaymentModule = PaymentModule(PaymentService(PaymentRepository(database), mollie, orders))

/** The route test seam: installs the webhook on caller-provided implementations. */
internal fun Application.installPaymentModule(
    payments: PaymentOperations,
    webhookSecret: String,
): Unit = PaymentRoutes.install(this, payments, webhookSecret)

/**
 * Installs the payment webhook and returns the module's handle.
 *
 * Install it **after** the order module and hand it that module's [OrderPaymentGateway]: the
 * dependency runs payment → order, so an order exists long before anything can be paid for it, and
 * no order consumer ever compiles against the Mollie integration.
 *
 * The adapter is built here rather than inside [createPaymentModule] because it owns an HTTP
 * client, and this is the only place that has an application to tie its lifetime to.
 */
public fun Application.installPaymentModule(
    database: Database,
    settings: MollieSettings,
    orders: OrderPaymentGateway,
): PaymentModule {
    val mollie = MolliePaymentClient(settings)
    monitor.subscribe(ApplicationStopped) { mollie.close() }
    return createPaymentModule(database, mollie, orders).also { module ->
        installPaymentModule(module.operations, settings.webhookSecret)
    }
}
