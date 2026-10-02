package com.homesweetlove.hwpapp;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import com.homesweetlove.hwpapp.core.*;
import java.io.*;
import java.util.*;

/** Paginated table export of the active sheet's used range. */
final class SpreadsheetPdf {
  static void export(Spreadsheet sheet, int index, File file) throws Exception {
    int maxRow = 0, maxCol = 0;
    for (String ref : sheet.addresses(index)) {
      int[] p = Spreadsheet.coordinate(ref);
      maxCol = Math.max(maxCol, p[0]);
      maxRow = Math.max(maxRow, p[1]);
    }
    int pageRows = 24, pageCols = 6;
    long pages = (long) (maxRow / pageRows + 1) * (maxCol / pageCols + 1);
    if (pages > 300 || (long) (maxRow + 1) * (maxCol + 1) > 50000)
      throw new IOException("PDF로 내보낼 범위가 너무 큽니다. 사용하지 않는 셀을 비우거나 작은 시트로 나눠 주세요.");
    PdfDocument pdf = new PdfDocument();
    try {
      int pageNumber = 0;
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setTextSize(10);
      for (int startRow = 0; startRow <= maxRow; startRow += pageRows)
        for (int startCol = 0; startCol <= maxCol; startCol += pageCols) {
          PdfDocument.Page page =
              pdf.startPage(new PdfDocument.PageInfo.Builder(842, 595, ++pageNumber).create());
          Canvas c = page.getCanvas();
          paint.setColor(0xff191f28);
          paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
          paint.setTextSize(18);
          c.drawText(sheet.names.get(index), 28, 32, paint);
          paint.setTextSize(9);
          paint.setTypeface(Typeface.DEFAULT);
          c.drawText(
              Spreadsheet.address(startCol, startRow) + " · " + pageNumber + " / " + pages,
              28,
              49,
              paint);
          float width = 124, height = 20;
          for (int row = startRow; row <= Math.min(maxRow, startRow + pageRows - 1); row++)
            for (int col = startCol; col <= Math.min(maxCol, startCol + pageCols - 1); col++) {
              float x = 28 + (col - startCol) * width, y = 66 + (row - startRow) * height;
              paint.setColor(0xffe5e8eb);
              paint.setStyle(Paint.Style.STROKE);
              c.drawRect(x, y, x + width, y + height, paint);
              paint.setStyle(Paint.Style.FILL);
              String ref = Spreadsheet.address(col, row), value = sheet.display(index, ref);
              DocumentStyle style = sheet.style(index, ref);
              paint.setTypeface(
                  Typeface.create(style.font, style.bold ? Typeface.BOLD : Typeface.NORMAL));
              paint.setTextSize(Math.min(12, Math.max(7, style.size)));
              paint.setColor(style.color);
              int length = paint.breakText(value, true, width - 8, null);
              String display =
                  length < value.length()
                      ? value.substring(0, Math.max(0, length - 1)) + "…"
                      : value;
              float tx =
                  style.alignment.equals("right")
                      ? x + width - 4 - paint.measureText(display)
                      : style.alignment.equals("center")
                          ? x + (width - paint.measureText(display)) / 2
                          : x + 4;
              c.save();
              c.clipRect(x + 2, y + 1, x + width - 2, y + height - 1);
              c.drawText(display, tx, y + 14, paint);
              c.restore();
            }
          pdf.finishPage(page);
        }
      try (FileOutputStream out = new FileOutputStream(file)) {
        pdf.writeTo(out);
      }
    } finally {
      pdf.close();
    }
  }
}
