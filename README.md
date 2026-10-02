[English](README.md) | [한국어](README.ko.md)

# HWP Desk for Android

A local document viewer and content editor for HWP, HWPX, PDF and Excel. Android 8.0+; no server, account, API key or network permission.

| Format | View and edit | Export |
| --- | --- | --- |
| HWP 5 / HWPX | Text, find/replace, font, size, bold, alignment, insert/delete paragraphs; page layout preview with tables and embedded images | Original format or PDF |
| PDF | Rendered pages, Korean text/ink, transparent signature images with size and placement; rotate/delete/reorder/merge/extract pages | PDF |
| XLSX / XLS | Sheet and cell navigation, font/size/bold/alignment, general/number/currency/date/percentage formatting; formulas and calculated results; find/replace | Original format |

Every completed edit creates a durable private-device checkpoint. Recover the latest document after restarting, undo/redo up to 20 checkpoints, or delete recovery data from Tools. Opening a new document replaces the previous document's recovery history. Files are always exported through Android's document picker.

Tap a paragraph or cell for its edit menu. Use **Tools** for find/replace, Hangul layout/PDF export, or PDF page/signature operations. For a signature, import a transparent PNG, enter its width as a percentage of the page, then tap its location. Undo and repeat insertion to change an already placed signature.

## Build and install

Use Android Studio with JDK 17, Android SDK 35 and Gradle 8.9:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows: `gradlew.bat testDebugUnitTest lintDebug assembleDebug`.

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Every main-branch push also builds it in [GitHub Actions](../../actions). Open the successful **Android APK** run and download **HWP-Desk-debug-apk**. This is a debug-signed APK for personal testing; enable installation from that download source. A production release requires your own stable signing key, which is never committed.

## Supported scope

- The local Hangul renderer reads physical paper size/margins, styled paragraphs, column widths, table cells and embedded raster images. It produces a paginated PDF using Android's native PDF canvas. This is a compatibility renderer, not the Hancom layout engine: floating/wrapped object positions, row-spanning cells, advanced borders, equations, vector drawings and multi-column layouts can differ or be unsupported. Row-spanning cells are expanded into rows; headers/footers/notes are placed in the content flow. The preview displays notices about detected limitations. Verify complex documents before sharing their PDF.
- Font names are stored in the source document. The device substitutes missing fonts in preview/PDF. The renderer recalculates pagination rather than reproducing existing Hangul line segmentation. Existing HWP controls and embedded assets are retained on source-format export. Formatting a HWP text block applies to its paragraph, including its other runs. HWPX formatting applies to its run and the paragraph alignment.
- HWP before version 5, encrypted/distribution documents and range-tagged text editing are unsupported. Paragraphs containing structural controls cannot be deleted. The last paragraph of a container cannot be deleted. HWPX text nodes containing child controls are read-only. Find/replace operates on editable text runs; matches spanning separate runs are not joined.
- PDF text/ink/signatures are flattened overlays. The original PDF text is not rewritten. Every edit is baked into its checkpoint; undo restores the preceding checkpoint. Page extraction saves a chosen subset (repeat with other ranges to split a document). Rotation and image placement respect rotated crop boxes. Encrypted/restricted PDFs, some JPX images and preservation of signature validity are unsupported. Outline/page-label updates are not implemented for reordered/merged documents.
- The formula interpreter supports arithmetic, powers, percentages, comparisons, concatenation, same/cross-sheet references and bounded ranges. Functions: `SUM`, `AVERAGE`, `MIN`, `MAX`, `COUNT`, `IF`, `IFERROR`, `ROUND`, `ABS`, `AND`, `OR`. Unsupported functions show `#UNSUPPORTED(name)` rather than an invented result. Cycles, invalid references and division by zero produce explicit errors. It is a subset of Excel, not a full Excel calculation engine. Excel recalculates on reopening exports; the app does not write calculated caches.
- Excel date/number/currency/percentage display and basic style editing are supported. Custom complex formats are approximated. XLSX drawings/charts are retained but not displayed; merged cells are shown as underlying cells. Shared/array formula editing, protected sheets and Strict OOXML are unsupported. Find/replace changes values, not formula expressions. Leading-zero identifiers and numbers beyond 15 significant digits remain text.
- XLS uses JExcelAPI's BIFF8 copy writer. Unsupported legacy drawings/macros may not survive export. Verify those documents in Excel.
- Input limit: 64 MB; expanded XML packages: 128 MB / 10,000 entries; PDF layout/merge: 300 pages. A recovery snapshot is limited to 96 MB. History retains at most 20 checkpoints and trims oldest records above 192 MB; a single current checkpoint is retained. Export errors roll edits back to the preceding checkpoint. The last in-progress stroke/dialog is not committed until applied. Recovery files are stored in Android's private application files and disappear on app-data deletion/uninstall.

## Verification

Unit tests cover source-format round trips, Unicode and control preservation, HWP/HWPX styles and paragraph insertion/deletion, XLSX formatting and Excel date rules, calculated formulas/cycles/errors, durable undo/redo and failed checkpoints, XML bounds and page range validation. Android tests exercise PDF crop/rotation alignment, signatures and page management, and actual Hangul-to-PDF rendering. CI runs tests, lint, APK compilation and emulator tests. Physical-device testing remains advisable before production release.

## Dependencies

- [HWPLib](https://github.com/neolord0/hwplib), Apache-2.0
- [PDFBox Android](https://github.com/TomRoush/PdfBox-Android), Apache-2.0
- [JExcelAPI](https://jexcelapi.sourceforge.net/), LGPL-2.1

See [THIRD_PARTY.md](THIRD_PARTY.md). App code is MIT licensed.
