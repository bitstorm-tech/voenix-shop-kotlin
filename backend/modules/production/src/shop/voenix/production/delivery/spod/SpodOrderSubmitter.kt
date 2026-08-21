package shop.voenix.production.delivery.spod

import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionSource
import shop.voenix.production.SpodProductRef
import shop.voenix.production.delivery.ProductionDeliveryDestination
import shop.voenix.production.delivery.resolveOrder
import shop.voenix.production.delivery.rethrowCancellationOrError

/**
 * The worker stage that prepares every print-on-demand job: upload the print images as designs,
 * create the partner's order in state `NEW`, persist the id it answers with, and confirm it. When
 * the confirmation goes through, the job's `prepared_at` is set — this channel's counterpart of
 * "the PDF exists" on the SFTP side, and the moment the job becomes shippable.
 *
 * The stage scans jobs in ascending id order, makes **one** attempt per job per scan, and records
 * every failure as a bounded code on the job's order row. Nothing here retries by itself; a job
 * that failed simply stays open and is picked up again by a later scan, exactly like every other
 * production stage.
 *
 * ### Why the order of the four steps is the whole design
 *
 * The partner offers no idempotency mechanism for `POST /orders`, no order list, and no lookup by
 * our own `externalOrderReference`. An order can only ever be fetched by the id its creation
 * answered with — so if that id is lost, the order is lost with it, and no amount of retrying can
 * find it again. Three rules follow, and they are the reason the steps are ordered as they are
 * ([ADR 0002](../../../../../../../../docs/adr/0002-production-fulfillment-channels.md), decision
 * 4):
 *
 * 1. **The order is always created `NEW`.** The partner would happily create it confirmed in one
 *    call; this shop never asks for that. A `NEW` order produces nothing and charges nothing, which
 *    is what makes an orphan affordable.
 * 2. **The id is persisted before anything else happens**
 *    ([SpodOrderRepository.recordCreatedOrder], in its own transaction). A crash between creation
 *    and confirmation then costs one confirm call on the next scan, not an untraceable order.
 * 3. **An ambiguous creation permits exactly one re-create.** A timeout, a reset connection, an
 *    unreadable answer, or a `5xx` may or may not have created an order. The first one is repeated
 *    — worst case one inert orphan. The second quarantines the job as `OUTCOME_UNKNOWN`, and a
 *    human reads the partner's backoffice. A refusal the partner *stated* (`4xx`) is not ambiguous
 *    at all: nothing was created, and the job is simply retried later.
 *
 * The confirmation follows the same logic from the other end: it reads `GET /orders/{id}` first and
 * confirms only while the order is still `NEW`, so a repeated attempt after a crash never confirms
 * twice.
 */
internal class SpodOrderSubmitter(
    private val source: ProductionSource,
    private val orders: SpodOrderRepository,
    client: SpodClient,
) {
    private val creator = SpodOrderCreator(orders, client)
    private val confirmer = SpodOrderConfirmer(orders, client)

    suspend fun submitOpenJobs() {
        orders.openJobs().forEach { job ->
            if (currentCoroutineContext().isActive && orders.startAttempt(job.id)) {
                attempt(job)
            }
        }
    }

    /**
     * One attempt for one job. An unexpected failure is a retryable background failure like every
     * other: it is logged in full — nothing of the partner's is in it — and recorded as a bounded
     * code, so the job stays open instead of stopping the scan for its siblings.
     */
    private suspend fun attempt(job: OpenSpodJob) {
        val result = runCatching { submit(job) }
        result.exceptionOrNull()?.let { failure ->
            failure.rethrowCancellationOrError()
            logger.error("Production job {} submission failed unexpectedly", job.id, failure)
            orders.fail(job.id, SpodSubmissionError.SUBMISSION_FAILED)
        }
    }

    private suspend fun submit(job: OpenSpodJob) {
        val order = source.resolveOrder(job.orderId) { code -> orders.recordFailure(job.id, code) }
        if (order != null) submitOrder(job, order)
    }

    private suspend fun submitOrder(job: OpenSpodJob, order: ProductionData) {
        when (val lookup = orders.destination(job.supplierId)) {
            SpodDestinationLookup.Missing ->
                orders.fail(job.id, SpodSubmissionError.DESTINATION_MISSING)
            SpodDestinationLookup.Disabled ->
                orders.fail(job.id, SpodSubmissionError.DESTINATION_DISABLED)
            is SpodDestinationLookup.Found -> submitTo(job, order, lookup.destination)
        }
    }

    private suspend fun submitTo(
        job: OpenSpodJob,
        order: ProductionData,
        destination: ProductionDeliveryDestination.Spod,
    ) {
        val lines = resolveLines(job, order)
        if (lines != null) submitLines(SpodJobContext(job, order, destination, lines))
    }

    /**
     * Creates the order unless a previous scan already did, then confirms it. `spodOrderId` being
     * present is exactly the crash-recovery case: the id was persisted, the confirmation was not
     * reached, and this scan re-enters at the confirmation without creating a second order.
     */
    private suspend fun submitLines(context: SpodJobContext) {
        val spodOrderId = context.job.spodOrderId ?: creator.create(context)
        if (spodOrderId != null) confirmer.confirm(context, spodOrderId)
    }

    /**
     * The job's item lines with their 1-based positions, or `null` when one of them cannot be
     * ordered — with the reason already recorded.
     *
     * The positions are the split's: the supplier's share of the order in source order, which is
     * exactly what `production_job_items` holds. That is what lets the check below work at all.
     *
     * Two of the three checks are the safety net of ADR 0002, decision 8. The partner's three ids
     * are resolved from *today's* master data on every load, so a mapping fix heals every pending
     * order — but the same liveness would let a corrected mapping silently turn a paid "Black / M"
     * into a different garment. So the composed variant name of today is compared against the name
     * the order line was split with, and a disagreement refuses the whole job.
     */
    private suspend fun resolveLines(job: OpenSpodJob, order: ProductionData): List<SpodLine>? {
        val snapshot = orders.itemVariantNames(job.id)
        val lines =
            order.items
                .filter { item -> item.supplierId == job.supplierId }
                .mapIndexed { index, item -> SpodLine(position = index + 1, item = item) }
        val problem = lines.firstNotNullOfOrNull { line -> line.problem(snapshot) }
        problem?.let { code -> orders.fail(job.id, code) }
        return lines.takeIf { problem == null }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SpodOrderSubmitter::class.java)
    }
}

/**
 * The creation half of the protocol: convert and upload the designs, build the order, send it, and
 * persist the id before anything else happens.
 */
private class SpodOrderCreator(
    private val orders: SpodOrderRepository,
    private val client: SpodClient,
) {
    /**
     * The partner's order id, or `null` when this attempt did not get that far.
     *
     * The phone is checked before a single byte is uploaded. Checkout already requires one whenever
     * the cart holds a print-on-demand line (ADR 0002, decision 6) and the partner rejects an order
     * without one, so this is defensive — but an order that cannot be created should not spend a
     * design upload on finding that out.
     */
    suspend fun create(context: SpodJobContext): String? {
        val phone = context.order.customerPhone?.takeIf(String::isNotBlank)
        return if (phone == null) {
            null.also { orders.fail(context.job.id, SpodSubmissionError.PHONE_MISSING) }
        } else {
            uploadDesigns(context)?.let { designs -> send(context, designs, phone) }
        }
    }

    /**
     * The design id of every line, uploading the ones no earlier scan uploaded.
     *
     * Uploads are grouped by print image, not by line: two lines of the same job that print the
     * same image share one design. Whatever *was* uploaded is persisted even when a later upload of
     * the same attempt fails, which is what makes a re-scan skip the positions it already has.
     */
    private suspend fun uploadDesigns(context: SpodJobContext): Map<Int, String>? {
        val stored = orders.designs(context.job.id)
        val pending =
            context.lines
                .filter { line -> stored[line.position] == null }
                .groupBy { line -> line.item.imagePath }
        val uploaded = mutableMapOf<Int, String>()
        val failure =
            pending.entries.firstNotNullOfOrNull { (path, group) ->
                upload(context, path, group, uploaded)
            }
        orders.recordDesigns(context.job.id, uploaded)
        failure?.let { code -> orders.recordFailure(context.job.id, code) }
        return (stored + uploaded).takeIf { failure == null }
    }

    /** One image converted and uploaded once, recorded for every line that prints it. */
    private suspend fun upload(
        context: SpodJobContext,
        path: Path?,
        group: List<SpodLine>,
        into: MutableMap<Int, String>,
    ): String? {
        val png =
            when (val converted = PrintImagePng.convert(path)) {
                is PrintImagePngResult.Failed -> return converted.error.name
                is PrintImagePngResult.Converted -> converted.bytes
            }
        val fileName = context.designFileName(group.first().position)
        return when (val answer = client.uploadDesign(context.destination, fileName, png)) {
            is SpodResult.Answered -> {
                group.forEach { line -> into[line.position] = answer.value }
                null
            }
            is SpodResult.Failed -> answer.error.name
        }
    }

    /**
     * Sends the creation with the default placement and, if the partner refuses it, once more with
     * a placement it says the product type actually offers.
     *
     * A second creation is only safe because the first one was refused with a `4xx`: the partner
     * stated it created nothing. Every other failure ends the attempt here.
     */
    private suspend fun send(
        context: SpodJobContext,
        designs: Map<Int, String>,
        phone: String,
    ): String? {
        val request = context.request(designs, phone, hotspots = emptyMap())
        return when (val created = client.createOrder(context.destination, request)) {
            is SpodResult.Answered -> persist(context, created.value)
            is SpodResult.Failed -> afterFailedCreate(context, designs, phone, created)
        }
    }

    private suspend fun afterFailedCreate(
        context: SpodJobContext,
        designs: Map<Int, String>,
        phone: String,
        failure: SpodResult.Failed,
    ): String? =
        when {
            failure.ambiguous -> null.also { recordAmbiguity(context, failure) }
            failure.error == SpodError.REFUSED -> withResolvedHotspots(context, designs, phone)
            else -> null.also { orders.fail(context.job.id, failure.error) }
        }

    private suspend fun withResolvedHotspots(
        context: SpodJobContext,
        designs: Map<Int, String>,
        phone: String,
    ): String? {
        val hotspots = resolveHotspots(context, designs) ?: return null
        val request = context.request(designs, phone, hotspots)
        return when (val created = client.createOrder(context.destination, request)) {
            is SpodResult.Answered -> persist(context, created.value)
            is SpodResult.Failed -> null.also { afterRejectedRetry(context, created) }
        }
    }

    private suspend fun afterRejectedRetry(context: SpodJobContext, failure: SpodResult.Failed) {
        if (failure.ambiguous) {
            recordAmbiguity(context, failure)
        } else {
            orders.fail(context.job.id, SpodSubmissionError.ORDER_CREATE_REJECTED)
        }
    }

    /**
     * A front placement per line, asked from the partner per distinct product type and design.
     *
     * The hotspot names are the partner's own vocabulary, and the endpoint answers only the ones
     * that fit this design on this product type. A front print is what this shop sells (issue #205,
     * product decision 3), so the first front hotspot on offer is taken; a product type that offers
     * none is [SpodSubmissionError.PLACEMENT_UNAVAILABLE] and a job for a human.
     */
    private suspend fun resolveHotspots(
        context: SpodJobContext,
        designs: Map<Int, String>,
    ): Map<Int, String>? {
        val byPlacement =
            context.lines.groupBy { line ->
                line.product.productTypeId to designs.getValue(line.position)
            }
        val resolved = mutableMapOf<Int, String>()
        val failure =
            byPlacement.entries.firstNotNullOfOrNull { (placement, group) ->
                resolveHotspot(context, placement, group, resolved)
            }
        failure?.let { code -> orders.recordFailure(context.job.id, code) }
        return resolved.takeIf { failure == null }
    }

    private suspend fun resolveHotspot(
        context: SpodJobContext,
        placement: Pair<Long, String>,
        group: List<SpodLine>,
        into: MutableMap<Int, String>,
    ): String? {
        val (productTypeId, designId) = placement
        val answer = client.availableHotspots(context.destination, productTypeId, designId)
        return when (answer) {
            is SpodResult.Failed -> answer.error.name
            is SpodResult.Answered -> {
                val hotspot = answer.value.firstOrNull { name -> name.contains(FRONT_VIEW) }
                hotspot?.let { name -> group.forEach { line -> into[line.position] = name } }
                if (hotspot == null) SpodSubmissionError.PLACEMENT_UNAVAILABLE.name else null
            }
        }
    }

    /**
     * Writes the id down before the confirmation is even attempted. The guard on `PENDING` cannot
     * normally fail — the state was read at the start of this scan — so a refused write means the
     * row moved underneath this attempt, and the id in hand belongs to an order nobody will ever
     * fetch again.
     */
    private suspend fun persist(context: SpodJobContext, spodOrderId: String): String? {
        val stored = orders.recordCreatedOrder(context.job.id, spodOrderId)
        if (!stored) {
            logger.error(
                "Production job {}: the created SPOD order id could not be stored",
                context.job.id,
            )
            orders.fail(context.job.id, SpodSubmissionError.ORDER_ID_NOT_STORED)
        }
        return spodOrderId.takeIf { stored }
    }

    private suspend fun recordAmbiguity(context: SpodJobContext, failure: SpodResult.Failed) {
        val count = orders.recordAmbiguousCreate(context.job.id, failure.error.name)
        logger.error(
            "Production job {}: SPOD order creation outcome unknown ({} of {} allowed)",
            context.job.id,
            count,
            MAX_AMBIGUOUS_CREATES,
        )
    }

    private companion object {
        const val MAX_AMBIGUOUS_CREATES = 2
        val logger: Logger = LoggerFactory.getLogger(SpodOrderCreator::class.java)
    }
}

/** The confirming half: read the order's state first, confirm only what is still `NEW`. */
private class SpodOrderConfirmer(
    private val orders: SpodOrderRepository,
    private val client: SpodClient,
) {
    suspend fun confirm(context: SpodJobContext, spodOrderId: String) {
        when (val state = client.getOrder(context.destination, spodOrderId)) {
            is SpodResult.Failed -> orders.fail(context.job.id, state.error)
            is SpodResult.Answered -> onState(context, spodOrderId, state.value)
        }
    }

    /**
     * `CONFIRMED` is not a failure but the crash-recovery case: the confirm call went through and
     * this backend never learned it, so the job is closed without confirming twice. Any other state
     * is one this stage refuses to act on — the webhook of T12 owns what happens to an order the
     * partner cancelled or flagged.
     */
    private suspend fun onState(
        context: SpodJobContext,
        spodOrderId: String,
        state: String,
    ) {
        when (state) {
            SPOD_STATE_NEW -> send(context, spodOrderId)
            SPOD_STATE_CONFIRMED -> complete(context)
            else -> orders.fail(context.job.id, SpodSubmissionError.ORDER_STATE_UNEXPECTED)
        }
    }

    private suspend fun send(context: SpodJobContext, spodOrderId: String) {
        when (val confirmed = client.confirmOrder(context.destination, spodOrderId)) {
            is SpodResult.Answered -> complete(context)
            is SpodResult.Failed ->
                if (confirmed.error == SpodError.REFUSED) {
                    orders.fail(context.job.id, SpodSubmissionError.ORDER_CONFIRM_FAILED)
                } else {
                    orders.fail(context.job.id, confirmed.error)
                }
        }
    }

    private suspend fun complete(context: SpodJobContext) {
        orders.completeConfirmation(context.job.id)
        logger.info(
            "Production job {} prepared: the SPOD order is confirmed (attempt {})",
            context.job.id,
            context.job.attemptCount + 1,
        )
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(SpodOrderConfirmer::class.java)
    }
}

/** Everything one attempt works on, so the steps below take one parameter instead of four. */
private data class SpodJobContext(
    val job: OpenSpodJob,
    val order: ProductionData,
    val destination: ProductionDeliveryDestination.Spod,
    val lines: List<SpodLine>,
)

/** One item line of the job with the position `production_job_items` filed it under. */
private data class SpodLine(val position: Int, val item: ProductionItem) {
    /** Valid only after [problem] answered `null`, which is the only way a line ever travels. */
    val product: SpodProductRef
        get() = checkNotNull(item.spodProduct)

    fun problem(snapshot: Map<Int, String>): SpodSubmissionError? =
        when {
            item.spodProduct == null -> SpodSubmissionError.ITEM_WITHOUT_SPOD_PRODUCT
            snapshot[position] == null -> SpodSubmissionError.ITEM_SNAPSHOT_MISSING
            snapshot[position] != item.variantName -> SpodSubmissionError.SPOD_MAPPING_CHANGED
            else -> null
        }
}

/**
 * The order this job becomes.
 *
 * [SpodOrderRequest.externalOrderReference] is deterministic — `ORD-{orderId}-JOB-{jobId}` — for
 * two reasons: the webhook finds the job by it without a second lookup table, and a human comparing
 * this shop's admin with the partner's backoffice reads the same string on both screens.
 *
 * The lines are grouped the way the partner groups them: one entry per product type and design,
 * with one quantity line per size and colour. Two shirt lines that differ only in size therefore
 * travel as one entry with two quantity lines, which is also how the partner prices and packs them.
 */
private fun SpodJobContext.request(
    designs: Map<Int, String>,
    phone: String,
    hotspots: Map<Int, String>,
): SpodOrderRequest =
    SpodOrderRequest(
        externalOrderReference = spodOrderReference(order.orderId, job.id),
        email = order.customerEmail,
        phone = phone,
        shipping = SpodShipping(address = order.address()),
        oneTimeItems =
            lines
                .groupBy { line -> line.product.productTypeId to designs.getValue(line.position) }
                .map { (placement, group) ->
                    val (productTypeId, designId) = placement
                    SpodOneTimeItem(
                        productTypeId = productTypeId,
                        quantityItems =
                            group.map { line ->
                                SpodQuantityItem(
                                    quantity = line.item.quantity,
                                    sizeId = line.product.sizeId,
                                    appearanceId = line.product.appearanceId,
                                )
                            },
                        configurations =
                            listOf(
                                SpodConfiguration(
                                    image = SpodConfigurationImage(designId = designId),
                                    view = FRONT_VIEW,
                                    hotspot =
                                        hotspots[group.first().position] ?: DEFAULT_FRONT_HOTSPOT,
                                )
                            ),
                    )
                },
    )

/** The partner wants one street line; the shop stores two fields. An empty number adds nothing. */
private fun ProductionData.address(): SpodAddress =
    SpodAddress(
        firstName = shippingFirstName,
        lastName = shippingLastName,
        street =
            if (shippingHouseNumber.isBlank()) {
                shippingStreet
            } else {
                "$shippingStreet $shippingHouseNumber"
            },
        city = shippingCity,
        country = shippingCountry,
        zipCode = shippingPostalCode,
    )

private fun SpodJobContext.designFileName(position: Int): String =
    "${spodOrderReference(order.orderId, job.id)}-$position.png"

/** The reference both this shop and the partner's backoffice show for the same job. */
internal fun spodOrderReference(orderId: Long, jobId: Long): String = "ORD-$orderId-JOB-$jobId"

/**
 * The order and job a reference names, or `null` when the string is not one of ours.
 *
 * It is the reverse of [spodOrderReference] and lives beside it so the two can never drift apart.
 * The webhook needs it: a reported event carries this string, and reading the job out of it is what
 * lets a shipment be matched even when the partner's own order id was never stored — the one case
 * an ambiguous creation leaves behind.
 *
 * Both numbers are still only a claim from an untrusted body. The caller checks the job it found
 * against them; this function only parses.
 */
internal fun parseSpodOrderReference(value: String): SpodReferenceIds? {
    val match = SPOD_ORDER_REFERENCE.matchEntire(value.trim()) ?: return null
    val orderId = match.groupValues[1].toLongOrNull() ?: return null
    val jobId = match.groupValues[2].toLongOrNull() ?: return null
    return SpodReferenceIds(orderId = orderId, jobId = jobId)
}

/** The two ids a `ORD-{orderId}-JOB-{jobId}` reference claims. */
internal data class SpodReferenceIds(val orderId: Long, val jobId: Long)

private val SPOD_ORDER_REFERENCE = Regex("ORD-(\\d{1,18})-JOB-(\\d{1,18})")

private suspend fun SpodOrderRepository.fail(jobId: Long, error: SpodSubmissionError) {
    recordFailure(jobId, error.name)
}

private suspend fun SpodOrderRepository.fail(jobId: Long, error: SpodError) {
    recordFailure(jobId, error.name)
}

/** The one view this shop prints on, and the placement it asks for first. */
private const val FRONT_VIEW = "FRONT"

private const val DEFAULT_FRONT_HOTSPOT = "MEDIUM_FRONT"

/** The two order states the partner's API knows. */
private const val SPOD_STATE_NEW = "NEW"

private const val SPOD_STATE_CONFIRMED = "CONFIRMED"

/**
 * The bounded vocabulary of submission failures that are this backend's own judgement rather than a
 * transport outcome; the names are the codes persisted in `production_spod_orders.last_error_code`.
 *
 * The stage also persists the names of [SpodError] and [PrintImageError], which is the whole set.
 * No provider text, token, or URL is ever among them.
 */
internal enum class SpodSubmissionError {
    /** The supplier has no print-on-demand destination at all. */
    DESTINATION_MISSING,

    /** The supplier's print-on-demand destination exists but is switched off. */
    DESTINATION_DISABLED,

    /** An item's variant carries no partner mapping; an admin has to fill the three ids in. */
    ITEM_WITHOUT_SPOD_PRODUCT,

    /** The job has no snapshotted line at this position — the split and the source disagree. */
    ITEM_SNAPSHOT_MISSING,

    /** Today's composed variant name is not the one the order line was split with (ADR 0002/8). */
    SPOD_MAPPING_CHANGED,

    /** The order carries no phone number, which the partner requires on every order. */
    PHONE_MISSING,

    /** The partner refused the creation, also with a placement it said it offers. */
    ORDER_CREATE_REJECTED,

    /** No front placement is available for this design on this product type. */
    PLACEMENT_UNAVAILABLE,

    /** The created order's id could not be written down; a human has to reconcile this one. */
    ORDER_ID_NOT_STORED,

    /** The partner refused the confirmation of an order that was still `NEW`. */
    ORDER_CONFIRM_FAILED,

    /** The partner reports a state this stage will not act on. */
    ORDER_STATE_UNEXPECTED,

    /** The attempt failed in a way that is this backend's bug, not the partner's answer. */
    SUBMISSION_FAILED,
}
