package shop.voenix.payment

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.OrderPaymentStatusSource

/**
 * The runtime handle of the installed payment module.
 *
 * It exports exactly two capabilities, and they point in opposite directions. [statusSource] is the
 * order module's [OrderPaymentStatusSource], which the composition root binds into the late-bound
 * source the order module was installed with. [starter] is this module's own [PaymentStarter], the
 * one way a caller — checkout, in both its journeys — asks for a payment to exist. Everything else
 * about a payment (the service, the repository, the table, the Mollie adapter) stays internal, and
 * the only other way in is the webhook route.
 *
 * The handle itself owns no lifecycle. The one piece there is — the Mollie adapter's HTTP client —
 * belongs to the *install function*, which is the only place with an application to close it on.
 */
public class PaymentModule
internal constructor(
    internal val operations: PaymentOperations,
    public val statusSource: OrderPaymentStatusSource,
    public val starter: PaymentStarter,
)

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
): PaymentModule =
    PaymentRepository(database).let { repository ->
        val service = PaymentService(repository, mollie, orders)
        PaymentModule(
            operations = service,
            statusSource = service,
            starter = PaymentLauncher(repository, mollie, orders),
        )
    }

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
 * no order consumer ever compiles against the Mollie integration. Bind [PaymentModule.statusSource]
 * into the order module's late-bound status source immediately afterwards; until that line runs, an
 * order read cannot answer a `paymentStatus` and says so loudly.
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
