# Third-party components

| Component | Version | License | Source |
| --- | --- | --- | --- |
| HWPLib | 1.1.11 | Apache-2.0 | https://github.com/neolord0/hwplib |
| PDFBox Android | 2.0.27.0 | Apache-2.0 | https://github.com/TomRoush/PdfBox-Android |
| JExcelAPI | 2.6.12 | LGPL-2.1 | https://sourceforge.net/projects/jexcelapi/files/jexcelapi/2.6.12/ |
| Gradle wrapper | 8.9 | Apache-2.0 | https://github.com/gradle/gradle |

Dependencies are linked without modifications. Their authors retain their copyrights. JExcelAPI's corresponding source is available at the linked release page. Users may replace the linked library and rebuild this application using the provided Gradle project; the app license does not prohibit reverse engineering for debugging modifications to that library. Some Java archive `META-INF` metadata entries are excluded during Android packaging to avoid duplicate-resource conflicts. This document and the linked upstream license texts are the primary third-party notices; do not assume every original JAR notice file is embedded in the APK.

Before distributing a signed APK, review the actual resolved artifacts and their license/NOTICE files, include any required copyright and NOTICE texts with the distributed app, and verify LGPL-2.1 Section 6 compliance for JExcelAPI. A link to upstream source alone may not satisfy every redistribution requirement. The complete LGPL-2.1 text is available at https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html . The full source corresponding to JExcelAPI 2.6.12 and a reproducible way to modify/relink it should remain available to recipients.
