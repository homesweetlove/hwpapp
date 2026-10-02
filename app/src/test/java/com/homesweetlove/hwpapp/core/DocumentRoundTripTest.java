package com.homesweetlove.hwpapp.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.writer.HWPWriter;
import kr.dogfoot.hwplib.reader.HWPReader;

public class DocumentRoundTripTest {
    private File packageFile(Map<String, String> entries, String suffix) throws Exception {
        File file = File.createTempFile("document-test-", suffix);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        return file;
    }
    private Map<String, String> xlsx() {
        Map<String, String> items = new LinkedHashMap<>();
        items.put("xl/workbook.xml", "<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='급여' sheetId='1' r:id='r1'/></sheets></workbook>");
        items.put("xl/_rels/workbook.xml.rels", "<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='r1' Target='worksheets/sheet1.xml' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet'/><Relationship Id='r2' Target='calcChain.xml' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/calcChain'/></Relationships>");
        items.put("xl/worksheets/sheet1.xml", "<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData><row r='1'><c r='A1' s='3' t='s'><v>0</v></c><c r='B1'><v>42</v></c></row></sheetData></worksheet>");
        items.put("xl/sharedStrings.xml", "<sst xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><si><t>원문</t></si></sst>");
        items.put("[Content_Types].xml", "<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Override PartName='/xl/calcChain.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml'/></Types>");
        items.put("xl/calcChain.xml", "<calcChain xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'/>");
        items.put("xl/styles.xml", "untouched style bytes");
        items.put("xl/media/image1.png", "untouched image bytes");
        return items;
    }
    @Test public void xlsxEditPreservesOtherCellsStylesAndPackageEntries() throws Exception {
        File input = packageFile(xlsx(), ".xlsx"), output = File.createTempFile("export-", ".xlsx");
        try (Spreadsheet doc = new Spreadsheet(input, false)) {
            assertEquals("원문", doc.value(0, "A1"));
            doc.set(0, "A1", "수정 😀"); doc.set(0, "C10", "=B1*2"); doc.set(0, "D10", "00123"); doc.save(output);
        }
        try (Spreadsheet reopened = new Spreadsheet(output, false)) {
            assertEquals("수정 😀", reopened.value(0, "A1")); assertEquals("42", reopened.value(0, "B1"));
            assertEquals("=B1*2", reopened.value(0, "C10")); assertEquals("00123", reopened.value(0, "D10"));
        }
        ZipXml saved = new ZipXml(output);
        assertFalse(saved.entries.containsKey("xl/calcChain.xml"));
        assertArrayEquals("untouched style bytes".getBytes(StandardCharsets.UTF_8), saved.entries.get("xl/styles.xml"));
        assertArrayEquals("untouched image bytes".getBytes(StandardCharsets.UTF_8), saved.entries.get("xl/media/image1.png"));
        assertEquals("3", ((org.w3c.dom.Element) saved.xml("xl/worksheets/sheet1.xml").getElementsByTagNameNS("*", "c").item(0)).getAttribute("s"));
        assertEquals("1", ((org.w3c.dom.Element) saved.xml("xl/workbook.xml").getElementsByTagNameNS("*", "calcPr").item(0)).getAttribute("fullCalcOnLoad"));
        input.delete(); output.delete();
    }
    @Test public void invalidFormulaDoesNotMutateCell() throws Exception {
        File file = packageFile(xlsx(), ".xlsx");
        try (Spreadsheet doc = new Spreadsheet(file, false)) {
            try { doc.set(0, "A1", "="); fail("Expected rejection"); } catch (IOException expected) { }
            assertEquals("원문", doc.value(0, "A1"));
        } finally { file.delete(); }
    }
    @Test public void hwpUnicodeSurvivesRepeatedEditsAndReload() throws Exception {
        File input = File.createTempFile("hangul-", ".hwp"), output = File.createTempFile("hangul-output-", ".hwp");
        HWPFile blank = BlankFileMaker.make();
        Paragraph paragraph = blank.getBodyText().getSectionList().get(0).getParagraph(0);
        paragraph.getText().addString("테스트");
        HWPWriter.toFile(blank, input.getAbsolutePath());
        HangulDocument doc = new HangulDocument(input, false);
        assertFalse(doc.blocks.isEmpty());
        doc.blocks.get(0).set("바꾼 글자 😀"); doc.blocks.get(0).set(""); doc.blocks.get(0).set("최종 😀"); doc.save(output);
        HangulDocument reopened = new HangulDocument(output, false);
        assertEquals("최종 😀", reopened.blocks.get(0).text);
        HWPFile read = HWPReader.fromFile(output);
        assertEquals(blank.getBodyText().getSectionList().get(0).getParagraph(0).getControlList().size(), read.getBodyText().getSectionList().get(0).getParagraph(0).getControlList().size());
        input.delete(); output.delete();
    }
    @Test public void hwpxPreservesMarkupAndChangesText() throws Exception {
        Map<String, String> parts = new LinkedHashMap<>(); parts.put("mimetype", "application/hwp+zip");
        parts.put("Contents/section0.xml", "<hp:sec xmlns:hp='urn:test'><hp:p><hp:run charPrIDRef='9'><hp:t>한글</hp:t></hp:run></hp:p></hp:sec>");
        File input = packageFile(parts, ".hwpx"), output = File.createTempFile("hwpx-out-", ".hwpx");
        HangulDocument doc = new HangulDocument(input, true); doc.blocks.get(0).set("수정"); doc.save(output);
        assertEquals("수정", new HangulDocument(output, true).blocks.get(0).text);
        assertEquals("9", ((org.w3c.dom.Element) new ZipXml(output).xml("Contents/section0.xml").getElementsByTagNameNS("*", "run").item(0)).getAttribute("charPrIDRef"));
        input.delete(); output.delete();
    }
    @Test public void legacyXlsEditsSurviveMultipleExports() throws Exception {
        File input = File.createTempFile("legacy-", ".xls"), output = File.createTempFile("legacy-out-", ".xls");
        jxl.write.WritableWorkbook workbook = jxl.Workbook.createWorkbook(input);
        workbook.createSheet("Sheet", 0).addCell(new jxl.write.Label(0, 0, "원문")); workbook.write(); workbook.close();
        try (Spreadsheet doc = new Spreadsheet(input, true)) {
            doc.set(0, "A1", "수정"); doc.set(0, "B2", "123"); doc.save(output);
            doc.set(0, "A1", "최종"); doc.save(output);
        }
        jxl.Workbook read = jxl.Workbook.getWorkbook(output);
        assertEquals("최종", read.getSheet(0).getCell(0, 0).getContents()); assertEquals("123", read.getSheet(0).getCell(1, 1).getContents());
        read.close(); input.delete(); output.delete();
    }
    @Test public void rejectsDtdAndOutOfRangeAddresses() throws Exception {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("evil.xml", "<!DOCTYPE a [<!ENTITY x 'expanded'>]><a>&x;</a>");
        File input = packageFile(parts, ".zip");
        try { new ZipXml(input).xml("evil.xml"); fail("DTD accepted"); } catch (IOException expected) { }
        for (String address : Arrays.asList("A0", "XFE1", "A1048577", "../A1")) {
            try { Spreadsheet.coordinate(address); fail("bad address " + address); } catch (IllegalArgumentException expected) { }
        }
        assertArrayEquals(new int[]{16383, 1048575}, Spreadsheet.coordinate("XFD1048576")); input.delete();
    }
}
