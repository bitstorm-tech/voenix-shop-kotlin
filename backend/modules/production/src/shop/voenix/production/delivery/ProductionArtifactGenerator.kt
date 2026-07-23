package shop.voenix.production.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.production.ProductionSource
import shop.voenix.production.pdf.ProductionArtifactStore
import shop.voenix.production.pdf.ProductionPdf
import shop.voenix.production.pdf.ProductionPdfRenderResult
import shop.voenix.production.pdf.ProductionPdfRenderer

/**
 * The worker stage that turns every open production job into its immutable artifact exactly once:
 * render the supplier's PDF, persist it under the private artifact root (temp write plus atomic
 * rename), then record digest and `generated_at` in the database — after that the job is closed and
 * later scans skip it. Failures are retryable background failures with bounded codes (the source
 * codes, the [shop.voenix.production.ProductionPdfError] names, and `ARTIFACT_WRITE_FAILED`): the
 * job stays open and recovers on a later scan once the cause healed.
 */
internal class ProductionArtifactGenerator(
    private val source: ProductionSource,
    private val jobs: ProductionJobRepository,
    private val renderer: ProductionPdfRenderer,
    private val artifacts: ProductionArtifactStore,
) {
    internal suspend fun generateMissingArtifacts() {
        jobs.openJobs().forEach { job ->
            if (currentCoroutineContext().isActive && jobs.startGenerationAttempt(job.id)) {
                generate(job.copy(generationAttemptCount = job.generationAttemptCount + 1))
            }
        }
    }

    private suspend fun generate(job: OpenProductionJob) {
        val order =
            source.resolveOrder(job.orderId) { code -> jobs.recordGenerationFailure(job.id, code) }
                ?: return
        val rendered = withContext(Dispatchers.IO) { renderer.render(order, job.supplierId) }
        when (rendered) {
            is ProductionPdfRenderResult.Failed ->
                jobs.recordGenerationFailure(job.id, code = rendered.error.name)
            is ProductionPdfRenderResult.Rendered -> persistArtifact(job, rendered.pdf)
        }
    }

    /**
     * Writes the artifact file first and the metadata second: a crash in between leaves an open job
     * whose next attempt regenerates and atomically replaces the file, so the recorded digest
     * always describes the bytes under the final path.
     */
    private suspend fun persistArtifact(job: OpenProductionJob, pdf: ProductionPdf) {
        val written = runCatching {
            withContext(Dispatchers.IO) { artifacts.write(job.id, job.fileName, pdf.bytes) }
        }
        written.exceptionOrNull()?.let { failure ->
            failure.rethrowCancellationOrError()
            logger.error("Production job {} artifact write failed", job.id, failure)
            jobs.recordGenerationFailure(job.id, code = "ARTIFACT_WRITE_FAILED")
            return
        }
        jobs.completeGeneration(job.id, contentSha256 = pdf.sha256)
        logger.info(
            "Production job {} artifact generated on attempt {}",
            job.id,
            job.generationAttemptCount,
        )
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(ProductionArtifactGenerator::class.java)
    }
}
