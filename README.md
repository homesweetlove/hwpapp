[English](README.md) | [한국어](README.ko.md)

# HWP Desk for Android

A local document viewer and content editor for HWP, HWPX, PDF and Excel. Android 8.0+; no server, account, API key or network permission.

| Format | View and edit | Export |
| --- | --- | --- |
| HWP 5 | Text runs in paragraphs, table cells, headers, footers and footnotes; search | `.hwp` |
| HWPX | Plain text nodes, including table text | `.hwpx` |
| PDF | Rendered pages, Korean text overlays and freehand ink; undo | `.pdf` |
| XLSX | Sheet selector, cell navigation, values and formula text | `.xlsx` |
| XLS | Legacy BIFF8 values and formulas via JExcelAPI | `.xls` |

Open a file through Android's document picker, tap a paragraph or cell to edit, and use **Save edited copy** to choose the output location. For PDF, enable the pen or enter text and tap the page to place it. Disable the pen to scroll. Unsaved edits prompt before closing or opening another file.

## Build and install

Use Android Studio with JDK 17, Android SDK 35 and Gradle 8.9:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows: `gradlew.bat testDebugUnitTest lintDebug assembleDebug`.

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Every main-branch push also builds it in [GitHub Actions](../../actions). Open the successful **Android APK** run and download **HWP-Desk-debug-apk**. This is a debug-signed APK for personal testing; enable installation from that download source. A production release requires your own stable signing key, which is never committed.

## Supported scope

- HWP/HWPX and Excel show content, not a pixel-perfect reproduction of their printed layout. PDF displays original pages.
- HWP preserves control objects, table structures and embedded assets. An edited run inherits its starting character style; mixed styles inside that run may change. Page/line layout is recalculated by Hangul on reopening. It does not insert/delete paragraphs or edit drawings. Range-tagged runs are read-only. HWP versions before 5, password-protected files and distribution documents are unsupported.
- HWPX text nodes containing child controls are read-only. Editing simple nodes preserves the remaining package entries.
- PDF edits add flattened transparent image overlays, including Korean text rendered with the device font. Existing PDF text is not rewritten. Encrypted/restricted PDFs and uncommon JPX images are unsupported. Existing digital signatures are not preserved as valid after editing.
- XLSX retains untouched ZIP entry bytes and cell style IDs. Formula edits use `=`; cached calculation is not evaluated locally. Excel recalculates when reopened. Shared/array formula ranges, protected sheets and Strict OOXML are unsupported. Merged cells are shown as underlying cells rather than merged visual regions. XLSX drawings/charts are preserved but not displayed.
- XLS uses JExcelAPI's BIFF8 copy writer; complex drawings/macros and unsupported legacy features may not survive. Use a copy and verify these files in Excel.
- Numeric values beyond 15 significant digits and leading-zero identifiers are saved as text. Existing dates are displayed as their stored value in XLSX. There is no formatting toolbar or local formula engine.
- Files are limited to 64 MB; expanded XML packages to 128 MB and 10,000 entries. Edits remain in memory until exported and do not survive process termination. Working copies are removed on normal document close; Android may evict cached files.

## Verification

Unit tests exercise HWP Unicode/control preservation and repeated editing, HWPX markup preservation, XLSX package/style preservation and recalculation metadata, XLS repeated exports, malformed XML and cell address validation. CI runs unit tests, Android lint and debug APK compilation. Physical-device UI verification is still required before a production release.

## Dependencies

- [HWPLib](https://github.com/neolord0/hwplib), Apache-2.0
- [PDFBox Android](https://github.com/TomRoush/PdfBox-Android), Apache-2.0
- [JExcelAPI](https://jexcelapi.sourceforge.net/), LGPL-2.1

See [THIRD_PARTY.md](THIRD_PARTY.md). App code is MIT licensed.
