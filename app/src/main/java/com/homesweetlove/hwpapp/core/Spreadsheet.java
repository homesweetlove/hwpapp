package com.homesweetlove.hwpapp.core;

import java.io.*;
import java.util.*;
import java.math.BigDecimal;
import org.w3c.dom.*;

/** XLSX package-preserving cell editor. Formulas recalculate in Excel when reopened. */
public final class Spreadsheet implements AutoCloseable {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private final ZipXml zip;
    private final List<String> strings = new ArrayList<>();
    public final List<String> names = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private final Map<Integer, Document> sheets = new HashMap<>();
    private final Map<Integer, Map<String, Element>> cellMaps = new HashMap<>();
    private jxl.Workbook xls;
    private jxl.write.WritableWorkbook writableXls;
    private File xlsTemp;
    public Spreadsheet(File file, boolean legacy) throws Exception {
        if (legacy) {
            zip = null;
            xls = jxl.Workbook.getWorkbook(file);
            xlsTemp = File.createTempFile("edited-", ".xls", file.getParentFile());
            writableXls = jxl.Workbook.createWorkbook(xlsTemp, xls);
            names.addAll(Arrays.asList(xls.getSheetNames()));
            return;
        }
        zip = new ZipXml(file);
        if (zip.entries.containsKey("xl/sharedStrings.xml")) {
            NodeList si = zip.xml("xl/sharedStrings.xml").getElementsByTagNameNS("*", "si");
            for (int i = 0; i < si.getLength(); i++) strings.add(textRuns((Element) si.item(i)));
        }
        Document workbook = zip.xml("xl/workbook.xml");
        if (!NS.equals(workbook.getDocumentElement().getNamespaceURI())) throw new IOException("Strict OOXML 문서는 지원하지 않습니다.");
        Document relations = zip.xml("xl/_rels/workbook.xml.rels");
        Map<String, String> targets = new HashMap<>();
        NodeList rels = relations.getElementsByTagNameNS("*", "Relationship");
        for (int i = 0; i < rels.getLength(); i++) {
            Element rel = (Element) rels.item(i);
            if (rel.getAttribute("TargetMode").equals("External")) continue;
            String target = rel.getAttribute("Target");
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            java.net.URI normalized = java.net.URI.create(path).normalize();
            if (normalized.toString().startsWith("../")) throw new IOException("잘못된 시트 경로입니다.");
            targets.put(rel.getAttribute("Id"), normalized.toString());
        }
        NodeList sheetList = workbook.getElementsByTagNameNS("*", "sheet");
        for (int i = 0; i < sheetList.getLength(); i++) {
            Element sheet = (Element) sheetList.item(i);
            String path = targets.get(sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"));
            if (path == null || !path.startsWith("xl/worksheets/")) continue;
            names.add(sheet.getAttribute("name")); paths.add(path);
        }
        if (names.isEmpty()) throw new IOException("워크시트를 찾을 수 없습니다.");
    }
    private Document sheet(int index) throws Exception {
        if (!sheets.containsKey(index)) {
            Document xml = zip.xml(paths.get(index));
            if (xml.getElementsByTagNameNS("*", "sheetProtection").getLength() != 0)
                throw new IOException("보호된 시트는 지원하지 않습니다.");
            sheets.put(index, xml);
            Map<String, Element> cells = new HashMap<>();
            NodeList list = xml.getElementsByTagNameNS("*", "c");
            for (int i = 0; i < list.getLength(); i++) { Element c = (Element) list.item(i); cells.put(c.getAttribute("r"), c); }
            cellMaps.put(index, cells);
        }
        return sheets.get(index);
    }
    public String value(int index, String address) throws Exception {
        int[] coordinates = coordinate(address);
        if (xls != null) {
            jxl.write.WritableSheet sheet = writableXls.getSheet(index);
            if (coordinates[0] >= sheet.getColumns() || coordinates[1] >= sheet.getRows()) return "";
            jxl.Cell cell = sheet.getCell(coordinates[0], coordinates[1]);
            if (cell instanceof jxl.FormulaCell) return "=" + ((jxl.FormulaCell) cell).getFormula();
            return cell.getContents();
        }
        sheet(index);
        Element c = cellMaps.get(index).get(address.toUpperCase(Locale.ROOT));
        if (c == null) return "";
        Element f = ZipXml.child(c, "f");
        if (f != null) return f.getTextContent().isEmpty() ? "[공유 수식 · 값 " + ZipXml.text(c, "v") + "]" : "=" + f.getTextContent();
        String value = ZipXml.text(c, "v");
        if (c.getAttribute("t").equals("s")) {
            int stringIndex = Integer.parseInt(value);
            return strings.get(stringIndex);
        }
        if (c.getAttribute("t").equals("inlineStr")) return textRuns(c);
        return value;
    }
    public void set(int index, String address, String value) throws Exception {
        address = address.toUpperCase(Locale.ROOT);
        if (value.length() > 32767) throw new IOException("셀은 32767자까지 입력할 수 있습니다.");
        if (value.equals("=")) throw new IOException("수식이 비어 있습니다.");
        int[] pos = coordinate(address);
        if (xls != null) {
            if (pos[0] > 255 || pos[1] > 65535) throw new IOException("XLS 범위를 벗어났습니다.");
            jxl.write.WritableSheet sheet = writableXls.getSheet(index);
            if (sheet.isProtected()) throw new IOException("보호된 시트는 수정할 수 없습니다.");
            jxl.format.CellFormat format = sheet.getCell(pos[0], pos[1]).getCellFormat();
            jxl.write.WritableCell cell;
            if (value.startsWith("=")) cell = new jxl.write.Formula(pos[0], pos[1], value.substring(1));
            else if (numeric(value)) cell = new jxl.write.Number(pos[0], pos[1], Double.parseDouble(value));
            else cell = new jxl.write.Label(pos[0], pos[1], value);
            if (format != null) cell.setCellFormat(format);
            sheet.addCell(cell); return;
        }
        Document xml = sheet(index);
        Map<String, Element> cells = cellMaps.get(index);
        // Reject shared/array/spill ranges rather than silently break their group.
        for (Element other : cells.values()) {
            Element f = ZipXml.child(other, "f");
            if (f != null && (f.hasAttribute("t") || f.hasAttribute("ref"))) {
                if (other.getAttribute("r").equals(address) || inRange(address, f.getAttribute("ref")))
                    throw new IOException("공유·배열 수식 영역은 수정할 수 없습니다. Excel에서 일반 수식으로 바꿔 주세요.");
            }
        }
        Element c = cells.get(address);
        if (c == null) {
            Element data = ZipXml.child(xml.getDocumentElement(), "sheetData");
            if (data == null) throw new IOException("시트 데이터가 없습니다.");
            Element row = null;
            for (Element r : ZipXml.children(data, "row")) if (r.getAttribute("r").equals("" + (pos[1] + 1))) row = r;
            if (row == null) {
                row = xml.createElementNS(NS, "row"); row.setAttribute("r", "" + (pos[1] + 1));
                Node before = null;
                for (Element r : ZipXml.children(data, "row")) if (Integer.parseInt(r.getAttribute("r")) > pos[1] + 1) { before = r; break; }
                data.insertBefore(row, before);
            }
            c = xml.createElementNS(NS, "c"); c.setAttribute("r", address);
            Node before = null;
            for (Element existing : ZipXml.children(row, "c")) if (coordinate(existing.getAttribute("r"))[0] > pos[0]) { before = existing; break; }
            row.insertBefore(c, before); cells.put(address, c);
        }
        for (String tag : Arrays.asList("f", "v", "is")) { Element old = ZipXml.child(c, tag); if (old != null) c.removeChild(old); }
        c.removeAttribute("t");
        if (value.startsWith("=")) {
            if (value.length() == 1) throw new IOException("수식이 비어 있습니다.");
            Element f = xml.createElementNS(NS, "f"); f.setTextContent(value.substring(1)); c.appendChild(f);
        } else if (numeric(value)) {
            Element v = xml.createElementNS(NS, "v"); v.setTextContent(value); c.appendChild(v);
        } else {
            c.setAttribute("t", "inlineStr");
            Element is = xml.createElementNS(NS, "is"), t = xml.createElementNS(NS, "t");
            t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
            t.setTextContent(value); is.appendChild(t); c.appendChild(is);
        }
        // dimension is only a cached hint; omit it so readers derive the current range.
        Element dimension = ZipXml.child(xml.getDocumentElement(), "dimension");
        if (dimension != null) xml.getDocumentElement().removeChild(dimension);
        zip.change(paths.get(index), xml);
    }
    private static boolean numeric(String value) {
        if (!value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) return false;
        try { return new BigDecimal(value).precision() <= 15 && Double.isFinite(Double.parseDouble(value)); }
        catch (NumberFormatException e) { return false; }
    }
    private static String textRuns(Element element) {
        StringBuilder out = new StringBuilder(); NodeList list = element.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < list.getLength(); i++) out.append(list.item(i).getTextContent()); return out.toString();
    }
    public static int[] coordinate(String address) {
        String upper = address.toUpperCase(Locale.ROOT).replace("$", "");
        if (!upper.matches("[A-Z]{1,3}[1-9][0-9]{0,6}")) throw new IllegalArgumentException("셀 주소를 A1 형식으로 입력하세요.");
        int col = 0, i = 0;
        while (i < upper.length() && Character.isLetter(upper.charAt(i))) col = col * 26 + upper.charAt(i++) - 'A' + 1;
        int row = Integer.parseInt(upper.substring(i));
        if (col > 16384 || row > 1048576) throw new IllegalArgumentException("Excel 셀 범위를 벗어났습니다.");
        return new int[]{col - 1, row - 1};
    }
    public static String address(int column, int row) {
        StringBuilder text = new StringBuilder(); int col = column + 1;
        while (col > 0) { text.insert(0, (char) ('A' + (col - 1) % 26)); col = (col - 1) / 26; }
        return text.append(row + 1).toString();
    }
    private static boolean inRange(String address, String range) {
        if (range.isEmpty()) return false;
        String[] ends = range.split(":"); int[] p = coordinate(address), a = coordinate(ends[0]), b = coordinate(ends[ends.length - 1]);
        return p[0] >= a[0] && p[0] <= b[0] && p[1] >= a[1] && p[1] <= b[1];
    }
    public void save(File file) throws Exception {
        if (xls != null) {
            writableXls.write(); writableXls.close(); writableXls = null;
            copy(xlsTemp, file);
            xls.close();
            xls = jxl.Workbook.getWorkbook(file);
            writableXls = jxl.Workbook.createWorkbook(xlsTemp, xls);
            return;
        }
        Document workbook = zip.xml("xl/workbook.xml");
        Element calc = ZipXml.child(workbook.getDocumentElement(), "calcPr");
        if (calc == null) { calc = workbook.createElementNS(NS, "calcPr"); workbook.getDocumentElement().appendChild(calc); }
        calc.setAttribute("fullCalcOnLoad", "1"); calc.setAttribute("forceFullCalc", "1"); calc.setAttribute("calcMode", "auto");
        zip.change("xl/workbook.xml", workbook);
        zip.remove("xl/calcChain.xml");
        Document relations = zip.xml("xl/_rels/workbook.xml.rels");
        removeMatching(relations, "Relationship", "Type", "/calcChain");
        zip.change("xl/_rels/workbook.xml.rels", relations);
        Document types = zip.xml("[Content_Types].xml");
        removeMatching(types, "Override", "PartName", "/calcChain.xml");
        zip.change("[Content_Types].xml", types);
        zip.save(file);
    }
    private static void removeMatching(Document document, String tag, String attribute, String ending) {
        NodeList list = document.getElementsByTagNameNS("*", tag);
        for (int i = list.getLength() - 1; i >= 0; i--) {
            Element node = (Element) list.item(i); if (node.getAttribute(attribute).endsWith(ending)) node.getParentNode().removeChild(node);
        }
    }
    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
    }
    public void close() {
        if (writableXls != null) try { writableXls.close(); } catch (Exception ignored) { }
        if (xls != null) xls.close();
        if (xlsTemp != null) xlsTemp.delete();
    }
}
