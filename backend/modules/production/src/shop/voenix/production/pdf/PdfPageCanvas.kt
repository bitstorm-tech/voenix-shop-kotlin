package shop.voenix.production.pdf

import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.util.Matrix

/** Shared physical layout constants of the production document. */
internal object ProductionPdfLayout {
    const val PAGE_WIDTH_MM = 239.0
    const val PAGE_HEIGHT_MM = 99.0
    const val SIDE_COLUMN_WIDTH_PT = 20f
    const val MM_PER_PT = 25.4 / 72.0
    const val SIDE_COLUMN_WIDTH_MM = SIDE_COLUMN_WIDTH_PT * MM_PER_PT

    fun mmToPt(mm: Double): Float = (mm / MM_PER_PT).toFloat()
}

/**
 * Low-level text drawing on one page: rotated side-column labels and the centered address block.
 * Owns every font-metric calculation so the composing renderer stays free of geometry noise.
 */
internal class PdfPageCanvas(
    private val content: PDPageContentStream,
    private val font: PDFont,
    private val pageWidth: Float,
    private val pageHeight: Float,
) {
    data class TextLine(val text: String, val size: Float, val bold: Boolean)

    /** Text reads bottom-to-top, centered in the left side column. */
    fun rotatedLeftColumnText(text: String, size: Float) {
        val columnCenter = ProductionPdfLayout.SIDE_COLUMN_WIDTH_PT / 2
        val x = columnCenter + (ascent(size) - descent(size)) / 2
        val y = (pageHeight - textWidth(text, size)) / 2
        rotatedText(text, size, HALF_PI, x, y)
    }

    /** Text reads top-to-bottom, centered in the right side column. */
    fun rotatedRightColumnText(text: String, size: Float) {
        val columnCenter = pageWidth - ProductionPdfLayout.SIDE_COLUMN_WIDTH_PT / 2
        val x = columnCenter - (ascent(size) - descent(size)) / 2
        val y = (pageHeight + textWidth(text, size)) / 2
        rotatedText(text, size, -HALF_PI, x, y)
    }

    /** Draws the lines as one block centered between the left column and the right page edge. */
    fun centeredTextBlock(lines: List<TextLine>) {
        val centerX =
            ProductionPdfLayout.SIDE_COLUMN_WIDTH_PT +
                (pageWidth - ProductionPdfLayout.SIDE_COLUMN_WIDTH_PT) / 2
        val totalHeight = lines.sumOf { (it.size * LINE_HEIGHT_FACTOR).toDouble() }.toFloat()
        var lineTop = (pageHeight + totalHeight) / 2
        for (line in lines) {
            val lineHeight = line.size * LINE_HEIGHT_FACTOR
            val gap = (lineHeight - ascent(line.size) - descent(line.size)) / 2
            val baseline = lineTop - gap - ascent(line.size)
            val x = centerX - textWidth(line.text, line.size) / 2
            content.beginText()
            content.setFont(font, line.size)
            if (line.bold) {
                // The bundled font family has no bold face; fill-plus-stroke approximates it.
                content.setRenderingMode(RenderingMode.FILL_STROKE)
                content.setLineWidth(line.size * BOLD_STROKE_FACTOR)
            } else {
                content.setRenderingMode(RenderingMode.FILL)
            }
            content.newLineAtOffset(x, baseline)
            content.showText(line.text)
            content.endText()
            lineTop -= lineHeight
        }
    }

    private fun rotatedText(text: String, size: Float, angleRadians: Double, x: Float, y: Float) {
        content.beginText()
        content.setFont(font, size)
        content.setRenderingMode(RenderingMode.FILL)
        content.setTextMatrix(Matrix.getRotateInstance(angleRadians, x, y))
        content.showText(text)
        content.endText()
    }

    private fun textWidth(text: String, size: Float): Float =
        font.getStringWidth(text) / GLYPH_SPACE_UNITS * size

    private fun ascent(size: Float): Float = font.fontDescriptor.ascent / GLYPH_SPACE_UNITS * size

    private fun descent(size: Float): Float =
        -font.fontDescriptor.descent / GLYPH_SPACE_UNITS * size

    private companion object {
        const val LINE_HEIGHT_FACTOR = 1.2f
        const val BOLD_STROKE_FACTOR = 0.03f
        const val GLYPH_SPACE_UNITS = 1000f
        val HALF_PI = Math.PI / 2
    }
}
