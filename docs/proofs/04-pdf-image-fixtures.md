# PDF and Image Fixture Proof

## Result

The Kotlin port now has a proof-only fixture test for PDF and image replacement risk:

- `OpenHTMLToPDF` renders an XHTML invoice fixture into PDF.
- `PDFBox` creates the same invoice content manually and also inspects/rasterizes both PDFs.
- `Scrimage` resizes a JPEG fixture with metadata, writes WebP at two quality levels, and reloads WebP.
- Source fixtures live under `backend/testResources/proofs/pdf-image-fixtures`.
- Generated proof artifacts are written under `backend/build/proofs/pdf-image-fixtures`.

No document or image workflow was ported. The libraries are test dependencies only.

## Visual Check

Generated rasters checked from:

- `backend/build/proofs/pdf-image-fixtures/pdf/openhtmltopdf-invoice.png`
- `backend/build/proofs/pdf-image-fixtures/pdf/pdfbox-invoice.png`

Observed result:

- OpenHTMLToPDF preserves the source layout shape: blue header, table, right-aligned totals.
- PDFBox output is visually correct for the same text but layout is fully manual.

## PDF Comparison

| Option | Fixture | Size | Time | Notes |
| --- | ---: | ---: | ---: | --- |
| OpenHTMLToPDF | `openhtmltopdf-invoice.pdf` | 1,837 bytes | 394 ms | Best fit for HTML/CSS documents. |
| PDFBox | `pdfbox-invoice.pdf` | 1,029 bytes | 2 ms | Good low-level PDF API; too manual for real layout. |

Recommendation: use OpenHTMLToPDF for invoice/order-style HTML document rendering if LGPL is acceptable. Keep PDFBox as the inspection, rendering, and low-level PDF utility. Do not build complex document layout directly with PDFBox unless the layout is tiny and static.

## Image Fixture

| File | Size | Time | Dimensions | Metadata marker |
| --- | ---: | ---: | --- | --- |
| `source-with-jpeg-comment.jpg` | 39,358 bytes | 0 ms | 640 x 400 | present |
| `resized-q90.webp` | 15,724 bytes | 259 ms | 320 x 200 | not retained |
| `resized-q40.webp` | 4,302 bytes | 28 ms | 320 x 200 | not retained |

The path proves:

- WebP write and read work on this macOS ARM64 environment.
- Resize preserves the expected 640 x 400 to 320 x 200 ratio.
- Quality materially changes output size.
- Source metadata is readable, but the WebP derivative does not retain the source comment.

Recommendation: use Scrimage as the Kotlin/JVM image proof candidate for low/medium volume WebP resize work. Before a production port, decide metadata policy explicitly; stripping metadata should be the default unless existing product behavior requires retention. For high throughput or container-hardening concerns, compare a libvips/imgproxy-style service before committing to in-process JVM image transforms.

## Risk Notes

- OpenHTMLToPDF needs XHTML-ish input and CSS support validation against real templates.
- OpenHTMLToPDF 1.1.40 uses PDFBox 3 support but depends on PDFBox 3.0.3 transitively; this proof also pins PDFBox 3.0.7 directly.
- Scrimage WebP uses embedded `cwebp`/`dwebp` binaries; verify container extraction, executable permissions, and Linux image compatibility.
- Metadata parity is not automatic. Preserve/strip behavior needs product-level decision.
- These timings are representative proof-fixture measurements, not a production corpus; run real production documents/images before porting workflows.

## Sources

- Apache PDFBox download/version notes: https://pdfbox.apache.org/download.html
- Maven Central `org.apache.pdfbox:pdfbox`: https://central.sonatype.com/artifact/org.apache.pdfbox/pdfbox
- Maven Central `io.github.openhtmltopdf:openhtmltopdf-pdfbox`: https://central.sonatype.com/artifact/io.github.openhtmltopdf/openhtmltopdf-pdfbox
- OpenHTMLToPDF README: https://github.com/danfickle/openhtmltopdf
- Scrimage quick start: https://sksamuel.github.io/scrimage/quick_start/
- Scrimage reading/writing: https://sksamuel.github.io/scrimage/io/
- Scrimage metadata: https://sksamuel.github.io/scrimage/metadata/
- Scrimage WebP: https://sksamuel.github.io/scrimage/webp/
