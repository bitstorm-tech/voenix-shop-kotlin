package shop.voenix.article.spod

import shop.voenix.spod.SpodAccess
import shop.voenix.spod.SpodClient
import shop.voenix.spod.SpodError
import shop.voenix.spod.SpodHotspotName
import shop.voenix.spod.SpodPreviewConfiguration
import shop.voenix.spod.SpodPreviewRequest
import shop.voenix.spod.SpodResult
import shop.voenix.spod.SpodView
import shop.voenix.spod.SpodViews

/**
 * The calibration half of one sync run: where the partner prints on each product type the run
 * meets, measured from the partner's own previews (SPOD sync decision 7; issue #260, decisions 7,
 * 10, 11, 12).
 *
 * One instance lives exactly as long as one run, and that is the whole lifecycle (decision 11): the
 * calibration design is uploaded once per run, on the first product type that needs it, and every
 * product type is measured once per run, on the first article of that type — every later article of
 * the type gets the same answer from the cache below. Only an answer is cached: a call the partner
 * refused is asked again by the next product type, or the next article of the same type, so one
 * network hiccup does not cost the rest of the run its measurements. Nothing survives the run, so a
 * changed mockup on the partner's side is simply measured again next time, and there is nothing to
 * invalidate. The design stays in the partner's account afterwards, like every per-order design
 * production uploads; the API has no call to delete one.
 *
 * Per product type the measurement costs `2 + candidates` paced API calls — the views, the hotspot
 * check of the design, and one preview per candidate hotspot — plus one unpaced CDN download per
 * preview. A front view offers five hotspots at most, so a product type costs at most seven paced
 * calls, and a catalog of twenty articles of one product type costs them once.
 */
internal class SpodCalibration(
    private val client: SpodClient,
    private val access: SpodAccess,
) {
    private var design: SpodResult<String>? = null
    private val views = mutableMapOf<Long, SpodResult<SpodViews>>()
    private val calibrations = mutableMapOf<Long, Calibration>()

    /**
     * The URL of the blank front mockup of colour [appearanceId] on [productTypeId], or `null` when
     * the partner answered no views, no front view, or no picture of that colour.
     */
    suspend fun mockupUrl(
        productTypeId: Long,
        appearanceId: Long,
    ): String? =
        views(productTypeId)
            .valueOrNull()
            ?.frontView()
            ?.images
            ?.firstOrNull { image -> image.appearanceId == appearanceId.toString() }
            ?.image

    /**
     * The hotspot and frame of [productTypeId], measured once per run. The first article of the
     * type decides which colour the previews are rendered on ([appearanceId]); the measured box is
     * the same on every colour (issue #260, verified facts), so the choice does not matter.
     */
    suspend fun calibrate(
        productTypeId: Long,
        appearanceId: Long,
    ): Calibration =
        calibrations[productTypeId]
            ?: measure(productTypeId, appearanceId).also { calibration ->
                if (calibration !is Calibration.Unavailable) {
                    calibrations[productTypeId] = calibration
                }
            }

    private suspend fun views(productTypeId: Long): SpodResult<SpodViews> =
        views[productTypeId]
            ?: client.views(access, productTypeId).also { answer ->
                if (answer is SpodResult.Answered) views[productTypeId] = answer
            }

    private suspend fun design(): SpodResult<String> =
        design
            ?: client
                .uploadDesign(access, CALIBRATION_DESIGN_FILE_NAME, calibrationDesignPng())
                .also { answer -> if (answer is SpodResult.Answered) design = answer }

    /**
     * Every front hotspot the partner would print the calibration design on, previewed and
     * measured; the largest measured box wins (decision 7), and a tie goes to the first in the
     * view's order. A product type without a measurable hotspot fails with every reason the
     * attempts produced, so the report can say what was tried — and is [Calibration.Unavailable]
     * instead when no attempt got as far as a picture to measure, because then nothing is known
     * about the product type at all.
     */
    @Suppress("ReturnCount")
    private suspend fun measure(
        productTypeId: Long,
        appearanceId: Long,
    ): Calibration {
        val front =
            when (val answer = views(productTypeId)) {
                is SpodResult.Failed ->
                    return Calibration.Unavailable("views: ${answer.error.name}")
                is SpodResult.Answered ->
                    answer.value.frontView() ?: return Calibration.Failed(NO_FRONT_VIEW)
            }
        val designId =
            when (val answer = design()) {
                is SpodResult.Failed ->
                    return Calibration.Unavailable("designs/upload: ${answer.error.name}")
                is SpodResult.Answered -> answer.value
            }
        val available =
            when (val answer = client.availableHotspots(access, productTypeId, designId)) {
                is SpodResult.Failed ->
                    return Calibration.Unavailable("hotspots: ${answer.error.name}")
                is SpodResult.Answered -> answer.value
            }
        val candidates =
            front.hotspots.map(SpodHotspotName::name).filter { hotspot -> hotspot in available }
        if (candidates.isEmpty()) return Calibration.Failed(NO_PRINTABLE_HOTSPOT)

        val attempts = candidates.map { hotspot ->
            preview(productTypeId, appearanceId, designId, hotspot)
        }
        val measured = attempts.filterIsInstance<Calibration.Measured>()
        if (measured.isNotEmpty()) {
            return measured.maxBy { it.frame.widthPct * it.frame.heightPct }
        }
        val detail = attempts.filterIsInstance<Calibration.Unmeasured>().joinToString { it.detail }
        return if (attempts.all { attempt -> attempt is Calibration.Unavailable }) {
            Calibration.Unavailable(detail)
        } else {
            Calibration.Failed(detail)
        }
    }

    /** One hotspot rendered on one colour and measured, or why it could not be. */
    @Suppress("ReturnCount")
    private suspend fun preview(
        productTypeId: Long,
        appearanceId: Long,
        designId: String,
        hotspot: String,
    ): Calibration {
        val request =
            SpodPreviewRequest(
                configurations = listOf(SpodPreviewConfiguration(designId, hotspot)),
                appearanceId = appearanceId.toString(),
                width = PREVIEW_EDGE_PIXELS,
                height = PREVIEW_EDGE_PIXELS,
            )
        val url =
            when (val answer = client.previews(access, productTypeId, request)) {
                is SpodResult.Failed ->
                    return Calibration.Unavailable("$hotspot previews: ${answer.error.name}")
                is SpodResult.Answered ->
                    answer.value.images
                        .firstOrNull { image ->
                            image.viewName.equals(FRONT_VIEW, ignoreCase = true)
                        }
                        ?.url
                        ?: return Calibration.Failed(
                            "$hotspot: ${SpodError.PROVIDER_ANSWER_UNREADABLE.name}"
                        )
            }
        val png =
            when (val answer = client.download(url + PNG_MEDIA_TYPE, access.timeoutSeconds)) {
                is SpodResult.Failed ->
                    return Calibration.Unavailable(
                        "$hotspot preview download: ${answer.error.name}"
                    )
                is SpodResult.Answered -> answer.value.bytes
            }
        return when (val measurement = measurePrintFrame(png)) {
            is PrintFrameMeasurement.Measured -> Calibration.Measured(hotspot, measurement.frame)
            is PrintFrameMeasurement.Failed ->
                Calibration.Failed("$hotspot: ${measurement.reason.name}")
        }
    }
}

/**
 * What one product type's calibration came to: the hotspot the customer gets and the frame the
 * editor shows, or why there is none, as a [Unmeasured.detail] in the shop's own words — the
 * reason, or one `HOTSPOT: REASON` per hotspot that was tried.
 *
 * The two ways to have no frame are told apart because they mean opposite things (issue #260,
 * decision 12). [Failed] is a *measurement*: the partner answered, and the product type cannot be
 * measured — the article is written without a frame, inactive, under `CALIBRATION_FAILED`.
 * [Unavailable] is a *transport* failure: the partner did not answer, so nothing is known, and the
 * article is skipped like an unreadable listing (SPOD sync decision 6), under
 * `CALIBRATION_UNAVAILABLE`.
 */
internal sealed interface Calibration {
    data class Measured(
        val hotspot: String,
        val frame: PrintFrame,
    ) : Calibration

    sealed interface Unmeasured : Calibration {
        val detail: String
    }

    data class Failed(override val detail: String) : Unmeasured

    data class Unavailable(override val detail: String) : Unmeasured
}

internal fun <T : Any> SpodResult<T>.valueOrNull(): T? = (this as? SpodResult.Answered)?.value

/** The view the sync prints on, matched by name because the partner numbers views per type. */
private fun SpodViews.frontView(): SpodView? = views.firstOrNull { view ->
    view.name.equals(FRONT_VIEW, ignoreCase = true)
}

private const val FRONT_VIEW = "FRONT"
private const val NO_FRONT_VIEW = "NO_FRONT_VIEW"
private const val NO_PRINTABLE_HOTSPOT = "NO_PRINTABLE_HOTSPOT"

/** Previews come as WebP unless the URL asks for PNG (issue #260, verified facts). */
private const val PNG_MEDIA_TYPE = ",mediaType=png"

/** The partner takes multiples of 50; 1000 px makes a pixel a tenth of a percent. */
private const val PREVIEW_EDGE_PIXELS = 1000
