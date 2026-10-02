package com.homesweetlove.hwpapp.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;

public final class NewDocuments {
  private NewDocuments() {}

  public static void hangul(File file) throws Exception {
    HWPWriter.toFile(BlankFileMaker.make(), file.getAbsolutePath());
  }

  public static void spreadsheet(File file, boolean budget) throws Exception {
    String main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
        rels = "http://schemas.openxmlformats.org/package/2006/relationships",
        office = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put(
        "[Content_Types].xml",
        "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default"
            + " Extension='rels'"
            + " ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default"
            + " Extension='xml' ContentType='application/xml'/><Override"
            + " PartName='/xl/workbook.xml'"
            + " ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml'/><Override"
            + " PartName='/xl/worksheets/sheet1.xml'"
            + " ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml'/></Types>");
    parts.put(
        "_rels/.rels",
        "<Relationships xmlns='"
            + rels
            + "'><Relationship Id='r1' Target='xl/workbook.xml' Type='"
            + office
            + "officeDocument'/></Relationships>");
    parts.put(
        "xl/workbook.xml",
        "<workbook xmlns='"
            + main
            + "' xmlns:r='"
            + office.substring(0, office.length() - 1)
            + "'><sheets><sheet name='"
            + (budget ? "가계부" : "Sheet1")
            + "' sheetId='1' r:id='r1'/></sheets><calcPr fullCalcOnLoad='1'/></workbook>");
    parts.put(
        "xl/_rels/workbook.xml.rels",
        "<Relationships xmlns='"
            + rels
            + "'><Relationship Id='r1' Target='worksheets/sheet1.xml' Type='"
            + office
            + "worksheet'/></Relationships>");
    parts.put(
        "xl/worksheets/sheet1.xml",
        "<worksheet xmlns='"
            + main
            + "'><sheetViews><sheetView workbookViewId='0'/></sheetViews><sheetData/></worksheet>");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      for (Map.Entry<String, String> e : parts.entrySet()) {
        out.putNextEntry(new ZipEntry(e.getKey()));
        out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    if (budget)
      try (Spreadsheet doc = new Spreadsheet(file, false)) {
        doc.paste(
            0,
            "A1",
            "항목\t예산\t지출\t남은 금액\n"
                + "식비\t500000\t0\t=B2-C2\n"
                + "교통\t100000\t0\t=B3-C3\n"
                + "생활\t200000\t0\t=B4-C4\n"
                + "합계\t=SUM(B2:B4)\t=SUM(C2:C4)\t=SUM(D2:D4)");
        DocumentStyle header = new DocumentStyle();
        header.bold = true;
        header.size = 12;
        for (String ref : Spreadsheet.range("A1:D1")) doc.format(0, ref, header);
        DocumentStyle money = new DocumentStyle();
        money.numberFormat = "currency";
        for (String ref : Spreadsheet.range("B2:D5")) doc.format(0, ref, money);
        File output = File.createTempFile("budget-", ".xlsx", file.getParentFile());
        try {
          doc.save(output);
          java.nio.file.Files.copy(
              output.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
          output.delete();
        }
      }
  }
}
