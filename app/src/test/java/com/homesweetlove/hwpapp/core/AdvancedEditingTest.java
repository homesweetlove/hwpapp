package com.homesweetlove.hwpapp.core;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.Test;

public class AdvancedEditingTest {
  private static File xlsx() throws Exception {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put(
        "xl/workbook.xml",
        "<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'"
            + " xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet"
            + " name='급여' sheetId='1' r:id='r1'/></sheets></workbook>");
    entries.put(
        "xl/_rels/workbook.xml.rels",
        "<Relationships"
            + " xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship"
            + " Id='r1' Target='worksheets/sheet1.xml'"
            + " Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet'/></Relationships>");
    entries.put(
        "[Content_Types].xml",
        "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'/>");
    entries.put(
        "xl/worksheets/sheet1.xml",
        "<worksheet"
            + " xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData/></worksheet>");
    File file = File.createTempFile("sheet-", ".xlsx");
    try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
      for (Map.Entry<String, String> e : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(e.getKey()));
        out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return file;
  }

  @Test
  public void formulasHandleReferencesRangesBranchesAndCycles() throws Exception {
    File file = xlsx();
    try (Spreadsheet doc = new Spreadsheet(file, false)) {
      doc.set(0, "A1", "10");
      doc.set(0, "A2", "20");
      doc.set(0, "A3", "=SUM(A1:A2)*2");
      assertEquals("60", doc.display(0, "A3"));
      doc.set(0, "A1", "30");
      assertEquals("100", doc.display(0, "A3"));
      doc.set(0, "B1", "=IF(A1>0,ROUND(12.345,2),1/0)");
      assertEquals("12.35", doc.display(0, "B1"));
      doc.set(0, "B2", "='급여'!A1+AVERAGE(A1:A2)");
      assertEquals("55", doc.display(0, "B2"));
      doc.set(0, "C1", "=C2");
      doc.set(0, "C2", "=C1");
      assertEquals("#CYCLE!", doc.display(0, "C1"));
      doc.set(0, "D1", "=UNSUPPORTED_FUNCTION(1)");
      assertEquals("#UNSUPPORTED(UNSUPPORTED_FUNCTION)", doc.display(0, "D1"));
      doc.set(0, "D2", "=1/0");
      assertEquals("#DIV/0!", doc.display(0, "D2"));
    } finally {
      file.delete();
    }
  }

  @Test
  public void formattingRoundTripsAndDatesUseExcelLeapYearRules() throws Exception {
    File file = xlsx(), output = File.createTempFile("formatted-", ".xlsx");
    try (Spreadsheet doc = new Spreadsheet(file, false)) {
      doc.set(0, "A1", "61");
      DocumentStyle style = new DocumentStyle();
      style.font = "Arial";
      style.size = 18;
      style.bold = true;
      style.alignment = "center";
      style.numberFormat = "date";
      doc.format(0, "A1", style);
      assertEquals("1900-03-01", doc.display(0, "A1"));
      doc.save(output);
    }
    try (Spreadsheet doc = new Spreadsheet(output, false)) {
      DocumentStyle style = doc.style(0, "A1");
      assertEquals(18, style.size, 0);
      assertTrue(style.bold);
      assertEquals("Arial", style.font);
      assertEquals("center", style.alignment);
      assertEquals("1900-03-01", doc.display(0, "A1"));
    }
    file.delete();
    output.delete();
  }

  @Test
  public void hangulFormattingAndParagraphChangesPreserveSection() throws Exception {
    File file = File.createTempFile("hangul-", ".hwp"),
        output = File.createTempFile("styled-", ".hwp");
    HWPFile model = BlankFileMaker.make();
    Paragraph p = model.getBodyText().getSectionList().get(0).getParagraph(0);
    p.getText().addString("바꾸기 원문");
    HWPWriter.toFile(model, file.getAbsolutePath());
    HangulDocument doc = new HangulDocument(file, false);
    TextBlock block = doc.blocks.get(0);
    DocumentStyle style = doc.style(block);
    style.font = "Arial";
    style.size = 20;
    style.bold = true;
    style.alignment = "right";
    doc.format(block, style);
    doc.insertAfter(block, "추가 문단");
    assertEquals(1, doc.replace("원문", "새 내용"));
    doc.save(output);
    HangulDocument reopened = new HangulDocument(output, false);
    assertEquals("바꾸기 새 내용", reopened.blocks.get(0).text);
    assertEquals("추가 문단", reopened.blocks.get(1).text);
    assertTrue(reopened.style(reopened.blocks.get(0)).bold);
    assertEquals(20, reopened.style(reopened.blocks.get(0)).size, 0);
    assertEquals("right", reopened.style(reopened.blocks.get(0)).alignment);
    reopened.deleteParagraph(reopened.blocks.get(1));
    reopened.save(file);
    assertEquals(1, new HangulDocument(file, false).blocks.size());
    file.delete();
    output.delete();
  }

  @Test
  public void hwpxStylesAndParagraphsSurvivePackageReload() throws Exception {
    File input = File.createTempFile("hwpx-", ".hwpx"),
        output = File.createTempFile("hwpx-out-", ".hwpx");
    Map<String, String> parts = new LinkedHashMap<>();
    parts.put("mimetype", "application/hwp+zip");
    parts.put(
        "Contents/section0.xml",
        "<hp:sec xmlns:hp='urn:p'><hp:p id='0' paraPrIDRef='0'><hp:run"
            + " charPrIDRef='0'><hp:t>원문</hp:t></hp:run></hp:p></hp:sec>");
    parts.put(
        "Contents/header.xml",
        "<hh:head xmlns:hh='urn:h'><hh:fontfaces><hh:fontface lang='HANGUL' fontCnt='1'><hh:font"
            + " id='0' face='Arial'/></hh:fontface></hh:fontfaces><hh:charProperties"
            + " itemCnt='1'><hh:charPr id='0' height='1100'><hh:fontRef"
            + " hangul='0'/></hh:charPr></hh:charProperties><hh:paraProperties"
            + " itemCnt='1'><hh:paraPr id='0'><hh:align"
            + " horizontal='LEFT'/></hh:paraPr></hh:paraProperties></hh:head>");
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(input))) {
      for (Map.Entry<String, String> e : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    HangulDocument doc = new HangulDocument(input, true);
    DocumentStyle style = doc.style(doc.blocks.get(0));
    style.font = "새 글꼴";
    style.size = 22;
    style.bold = true;
    style.alignment = "center";
    doc.format(doc.blocks.get(0), style);
    doc.insertAfter(doc.blocks.get(0), "새 문단");
    doc.save(output);
    HangulDocument read = new HangulDocument(output, true);
    assertEquals(2, read.blocks.size());
    assertEquals("새 문단", read.blocks.get(1).text);
    assertEquals("새 글꼴", read.style(read.blocks.get(0)).font);
    assertTrue(read.style(read.blocks.get(0)).bold);
    assertEquals("center", read.style(read.blocks.get(0)).alignment);
    read.deleteParagraph(read.blocks.get(1));
    read.save(input);
    assertEquals(1, new HangulDocument(input, true).blocks.size());
    input.delete();
    output.delete();
  }

  @Test
  public void durableUndoRedoBranchesAndFailedCheckpoints() throws Exception {
    File directory = Files.createTempDirectory("recovery-").toFile();
    SessionHistory h = new SessionHistory(directory);
    h.start("문서.hwp", "hwp", file -> Files.write(file.toPath(), new byte[] {1}));
    h.commit(file -> Files.write(file.toPath(), new byte[] {2}));
    h.commit(file -> Files.write(file.toPath(), new byte[] {3}));
    SessionHistory recovered = new SessionHistory(directory);
    assertEquals(3, Files.readAllBytes(recovered.current().toPath())[0]);
    assertEquals(2, Files.readAllBytes(recovered.undo().toPath())[0]);
    assertTrue(recovered.canRedo());
    recovered.commit(file -> Files.write(file.toPath(), new byte[] {4}));
    assertFalse(recovered.canRedo());
    assertEquals(4, Files.readAllBytes(new SessionHistory(directory).current().toPath())[0]);
    try {
      recovered.commit(
          file -> {
            throw new IOException("failed write");
          });
      fail();
    } catch (IOException expected) {
    }
    assertEquals(4, Files.readAllBytes(recovered.current().toPath())[0]);
    for (int i = 0; i < 25; i++)
      recovered.commit(file -> Files.write(file.toPath(), new byte[] {5}));
    int steps = 0;
    while (recovered.canUndo()) {
      recovered.undo();
      steps++;
    }
    assertEquals(19, steps);
    recovered.clear();
    assertFalse(new SessionHistory(directory).available());
    directory.delete();
  }

  @Test
  public void pageRangesValidatePermutationsAndExtractDescending() {
    assertEquals(Arrays.asList(2, 1, 0), PageRanges.parse("3-1", 3, true));
    assertEquals(Arrays.asList(0, 2), PageRanges.parse("1,3", 3, false));
    for (String input : Arrays.asList("1,1", "0", "4", "1,")) {
      try {
        PageRanges.parse(input, 3, true);
        fail(input);
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
