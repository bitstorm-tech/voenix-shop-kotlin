package shop.voenix.production

import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the module boundary: the public production API must never expose PDF-library types. The
 * renderer is an implementation detail that can be replaced without touching any consumer.
 */
internal class ProductionPublicApiTest {
    @Test
    fun `the public production API exposes no PDF library types`() {
        val publicApi =
            listOf(
                ProductionSource::class.java,
                ProductionData::class.java,
                ProductionItem::class.java,
                ProductionPdfGenerator::class.java,
                ProductionPdfResult::class.java,
                ProductionPdfResult.Generated::class.java,
                ProductionPdfResult.OrderNotFound::class.java,
                ProductionPdfResult.GenerationFailed::class.java,
                ProductionPdfDocument::class.java,
                ProductionPdfError::class.java,
                ProductionModule::class.java,
            )
        val offenders = publicApi.flatMap { type ->
            type.methods.filter(::declaredInThisModule).flatMap(::foreignPdfTypes) +
                type.constructors.flatMap { it.parameterTypes.filter(::isPdfLibraryType) }
        }
        assertTrue(offenders.isEmpty(), "PDF-library types leak into the public API: $offenders")
    }

    private fun declaredInThisModule(method: Method): Boolean =
        method.declaringClass.packageName.startsWith("shop.voenix")

    private fun foreignPdfTypes(method: Method): List<Class<*>> =
        (listOf(method.returnType) + method.parameterTypes).filter(::isPdfLibraryType)

    private fun isPdfLibraryType(type: Class<*>): Boolean =
        type.packageName.startsWith("org.apache.pdfbox") ||
            type.packageName.startsWith("org.apache.fontbox")
}
