package com.homesweetlove.hwpapp.core;

import java.io.*;
import java.math.BigDecimal;
import java.util.*;
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
  private final Map<String, Object> calculated = new HashMap<>();
  private int calculationVisits;

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
    if (!NS.equals(workbook.getDocumentElement().getNamespaceURI()))
      throw new IOException("Strict OOXML 문서는 지원하지 않습니다.");
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
      String path =
          targets.get(
              sheet.getAttributeNS(
                  "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id"));
      if (path == null || !path.startsWith("xl/worksheets/")) continue;
      names.add(sheet.getAttribute("name"));
      paths.add(path);
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
      for (int i = 0; i < list.getLength(); i++) {
        Element c = (Element) list.item(i);
        cells.put(c.getAttribute("r"), c);
      }
      cellMaps.put(index, cells);
    }
    return sheets.get(index);
  }

  public String value(int index, String address) throws Exception {
    int[] coordinates = coordinate(address);
    address = address(coordinates[0], coordinates[1]);
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
    if (f != null)
      return f.getTextContent().isEmpty()
          ? "[공유 수식 · 값 " + ZipXml.text(c, "v") + "]"
          : "=" + f.getTextContent();
    String value = ZipXml.text(c, "v");
    if (c.getAttribute("t").equals("s")) {
      int stringIndex = Integer.parseInt(value);
      return strings.get(stringIndex);
    }
    if (c.getAttribute("t").equals("inlineStr")) return textRuns(c);
    return value;
  }

  public void set(int index, String address, String value) throws Exception {
    calculated.clear();
    int[] canonical = coordinate(address);
    address = address(canonical[0], canonical[1]);
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
      else if (numeric(value))
        cell = new jxl.write.Number(pos[0], pos[1], Double.parseDouble(value));
      else cell = new jxl.write.Label(pos[0], pos[1], value);
      if (format != null) cell.setCellFormat(format);
      sheet.addCell(cell);
      return;
    }
    Document xml = sheet(index);
    for (Element merge :
        ZipXml.children(ZipXml.child(xml.getDocumentElement(), "mergeCells"), "mergeCell")) {
      String ref = merge.getAttribute("ref");
      if (inRange(address, ref) && !ref.split(":")[0].equals(address))
        throw new IOException("병합 셀의 왼쪽 위 셀만 수정할 수 있습니다.");
    }
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
      for (Element r : ZipXml.children(data, "row"))
        if (r.getAttribute("r").equals("" + (pos[1] + 1))) row = r;
      if (row == null) {
        row = xml.createElementNS(NS, "row");
        row.setAttribute("r", "" + (pos[1] + 1));
        Node before = null;
        for (Element r : ZipXml.children(data, "row"))
          if (Integer.parseInt(r.getAttribute("r")) > pos[1] + 1) {
            before = r;
            break;
          }
        data.insertBefore(row, before);
      }
      c = xml.createElementNS(NS, "c");
      c.setAttribute("r", address);
      Node before = null;
      for (Element existing : ZipXml.children(row, "c"))
        if (coordinate(existing.getAttribute("r"))[0] > pos[0]) {
          before = existing;
          break;
        }
      row.insertBefore(c, before);
      cells.put(address, c);
    }
    for (String tag : Arrays.asList("f", "v", "is")) {
      Element old = ZipXml.child(c, tag);
      if (old != null) c.removeChild(old);
    }
    c.removeAttribute("t");
    if (value.startsWith("=")) {
      if (value.length() == 1) throw new IOException("수식이 비어 있습니다.");
      Element f = xml.createElementNS(NS, "f");
      f.setTextContent(value.substring(1));
      c.appendChild(f);
    } else if (numeric(value)) {
      Element v = xml.createElementNS(NS, "v");
      v.setTextContent(value);
      c.appendChild(v);
    } else {
      c.setAttribute("t", "inlineStr");
      Element is = xml.createElementNS(NS, "is"), t = xml.createElementNS(NS, "t");
      t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
      t.setTextContent(value);
      is.appendChild(t);
      c.appendChild(is);
    }
    // dimension is only a cached hint; omit it so readers derive the current range.
    Element dimension = ZipXml.child(xml.getDocumentElement(), "dimension");
    if (dimension != null) xml.getDocumentElement().removeChild(dimension);
    zip.change(paths.get(index), xml);
  }

  public String display(int index, String address) throws Exception {
    int[] canonical = coordinate(address);
    address = address(canonical[0], canonical[1]);
    calculationVisits = 0;
    Object result = calculate(index, address, new HashSet<>(), 0);
    if (result instanceof FormulaEngine.Failure) return result.toString();
    DocumentStyle style = style(index, address);
    if (result instanceof Number) {
      double value = ((Number) result).doubleValue();
      if (style.numberFormat.equals("currency"))
        return "₩" + new java.text.DecimalFormat("#,##0.00").format(value);
      if (style.numberFormat.equals("number"))
        return new java.text.DecimalFormat("#,##0.##").format(value);
      if (style.numberFormat.equals("percent"))
        return new java.text.DecimalFormat("0.00%").format(value);
      if (style.numberFormat.equals("date")) {
        boolean mode1904 = false;
        if (zip != null) {
          Element properties =
              ZipXml.child(zip.xml("xl/workbook.xml").getDocumentElement(), "workbookPr");
          mode1904 =
              properties != null
                  && Arrays.asList("1", "true").contains(properties.getAttribute("date1904"));
        }
        java.time.LocalDate origin =
            java.time.LocalDate.of(mode1904 ? 1904 : 1899, mode1904 ? 1 : 12, mode1904 ? 1 : 31);
        long days = (long) Math.floor(value);
        if (!mode1904 && days >= 60) days--;
        if (!mode1904 && (long) value == 60) return "1900-02-29";
        return origin.plusDays(days).toString();
      }
    }
    return FormulaEngine.text(result);
  }

  private Object calculate(int index, String address, Set<String> seen, int depth) {
    String key = index + ":" + address;
    if (calculated.containsKey(key)) return calculated.get(key);
    if (++calculationVisits > 50000) return new FormulaEngine.Failure("#LIMIT!");
    if (depth > 64) return new FormulaEngine.Failure("#LIMIT!");
    if (!seen.add(key)) return new FormulaEngine.Failure("#CYCLE!");
    try {
      String raw = value(index, address);
      if (raw.startsWith("=")) {
        Object result =
            FormulaEngine.evaluate(
                raw,
                new FormulaEngine.Resolver() {
                  public Object cell(String sheetName, String ref) {
                    int target = sheetName == null ? index : -1;
                    if (sheetName != null)
                      for (int i = 0; i < names.size(); i++)
                        if (names.get(i).equalsIgnoreCase(sheetName)) {
                          target = i;
                          break;
                        }
                    return target < 0
                        ? new FormulaEngine.Failure("#REF!")
                        : calculate(target, ref, seen, depth + 1);
                  }

                  public boolean date1904() {
                    return Spreadsheet.this.date1904();
                  }
                });
        // A failed branch may have seen a cycle before IF selected a valid value.
        if (!(result instanceof FormulaEngine.Failure)) calculated.put(key, result);
        return result;
      }
      if (zip != null) {
        Element c = cellMaps.get(index).get(address);
        if (c != null) {
          String type = c.getAttribute("t");
          if (type.equals("e")) return new FormulaEngine.Failure(raw);
          if (type.equals("b")) return raw.equals("1");
          if (type.equals("s") || type.equals("inlineStr") || type.equals("str")) return raw;
        }
      } else {
        int[] coord = coordinate(address);
        jxl.write.WritableSheet sheet = writableXls.getSheet(index);
        if (coord[0] < sheet.getColumns() && coord[1] < sheet.getRows()) {
          jxl.Cell c = sheet.getCell(coord[0], coord[1]);
          if (c instanceof jxl.NumberCell) return ((jxl.NumberCell) c).getValue();
          if (c instanceof jxl.DateCell)
            return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(((jxl.DateCell) c).getDate());
          if (c instanceof jxl.BooleanCell) return ((jxl.BooleanCell) c).getValue();
          return raw;
        }
      }
      return raw.isEmpty() ? null : Double.valueOf(raw);
    } catch (Exception unsupported) {
      return new FormulaEngine.Failure("#VALUE!");
    } finally {
      seen.remove(key);
    }
  }

  public List<String> addresses(int index) throws Exception {
    if (xls != null) {
      List<String> list = new ArrayList<>();
      jxl.write.WritableSheet s = writableXls.getSheet(index);
      if ((long) s.getColumns() * s.getRows() > 100000) throw new IOException("검색 범위가 너무 큽니다.");
      for (int y = 0; y < s.getRows(); y++)
        for (int x = 0; x < s.getColumns(); x++)
          if (!s.getCell(x, y).getContents().isEmpty()) list.add(address(x, y));
      return list;
    }
    sheet(index);
    List<String> result = new ArrayList<>(cellMaps.get(index).keySet());
    result.sort(
        (a, b) -> {
          int[] x = coordinate(a), y = coordinate(b);
          return x[1] == y[1] ? Integer.compare(x[0], y[0]) : Integer.compare(x[1], y[1]);
        });
    return result;
  }

  public boolean date1904() {
    if (zip == null) return false;
    try {
      Element p = ZipXml.child(zip.xml("xl/workbook.xml").getDocumentElement(), "workbookPr");
      return p != null && Arrays.asList("1", "true").contains(p.getAttribute("date1904"));
    } catch (Exception e) {
      return false;
    }
  }

  public void recalculate() {
    calculated.clear();
  }

  public static List<String> range(String text) {
    String[] ends = text.trim().split(":", -1);
    if (ends.length > 2) throw new IllegalArgumentException("범위를 A1:C10 형식으로 입력하세요.");
    int[] a = coordinate(ends[0]), b = coordinate(ends[ends.length - 1]);
    long count = (long) (Math.abs(a[0] - b[0]) + 1) * (Math.abs(a[1] - b[1]) + 1);
    if (count > 10000) throw new IllegalArgumentException("한 번에 10,000셀까지 작업할 수 있습니다.");
    List<String> out = new ArrayList<>();
    for (int y = Math.min(a[1], b[1]); y <= Math.max(a[1], b[1]); y++)
      for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++) out.add(address(x, y));
    return out;
  }

  public String copyRange(int index, String text, boolean values) throws Exception {
    List<String> cells = range(text);
    int previous = -1;
    StringBuilder out = new StringBuilder();
    for (String ref : cells) {
      int row = coordinate(ref)[1];
      if (previous != -1) out.append(row == previous ? '\t' : '\n');
      String value = values ? display(index, ref) : value(index, ref);
      if (value.indexOf('\n') >= 0 || value.indexOf('\t') >= 0 || value.indexOf('"') >= 0)
        out.append('"').append(value.replace("\"", "\"\"")).append('"');
      else out.append(value);
      previous = row;
      if (out.length() > 1048576) throw new IOException("복사할 내용이 너무 큽니다.");
    }
    return out.toString();
  }

  public void paste(int index, String start, String text) throws Exception {
    int[] origin = coordinate(start);
    List<List<String>> rows = TabularText.parse(text);
    int count = 0;
    for (int y = 0; y < rows.size(); y++)
      for (int x = 0; x < rows.get(y).size(); x++) {
        if (++count > 10000) throw new IOException("10,000셀까지 붙여넣을 수 있습니다.");
        coordinate(address(origin[0] + x, origin[1] + y));
        if (xls != null && (origin[0] + x > 255 || origin[1] + y > 65535))
          throw new IOException("XLS 범위를 벗어났습니다.");
        if (rows.get(y).get(x).length() > 32767) throw new IOException("셀 내용이 너무 깁니다.");
      }
    for (int y = 0; y < rows.size(); y++)
      for (int x = 0; x < rows.get(y).size(); x++)
        set(index, address(origin[0] + x, origin[1] + y), rows.get(y).get(x));
  }

  public void addSheet(String name) throws Exception {
    if (name.trim().isEmpty()
        || name.length() > 31
        || name.matches(".*[\\\\/?*\\[\\]:].*")
        || name.startsWith("'")
        || name.endsWith("'")) throw new IOException("시트 이름은 1–31자이며 \\ / ? * [ ] : 를 포함할 수 없습니다.");
    for (String old : names)
      if (old.equalsIgnoreCase(name)) throw new IOException("이미 있는 시트 이름입니다.");
    if (names.size() >= 100) throw new IOException("100개 시트까지 지원합니다.");
    calculated.clear();
    if (xls != null) {
      writableXls.createSheet(name, names.size());
      names.add(name);
      return;
    }
    Document workbook = zip.xml("xl/workbook.xml");
    Element protection = ZipXml.child(workbook.getDocumentElement(), "workbookProtection");
    if (protection != null) throw new IOException("워크북이 보호되어 있습니다.");
    int id = 1;
    for (Element item :
        ZipXml.children(ZipXml.child(workbook.getDocumentElement(), "sheets"), "sheet"))
      id = Math.max(id, Integer.parseInt(item.getAttribute("sheetId")) + 1);
    String path = "xl/worksheets/desk-" + UUID.randomUUID() + ".xml",
        relId = "rDesk" + UUID.randomUUID().toString().replace("-", "");
    zip.entries.put(
        path,
        ("<worksheet xmlns='" + NS + "'><sheetData/></worksheet>")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Element item = workbook.createElementNS(NS, "sheet");
    item.setAttribute("name", name);
    item.setAttribute("sheetId", "" + id);
    item.setAttributeNS(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "r:id", relId);
    ZipXml.child(workbook.getDocumentElement(), "sheets").appendChild(item);
    zip.change("xl/workbook.xml", workbook);
    Document rels = zip.xml("xl/_rels/workbook.xml.rels");
    Element rel = rels.createElementNS(rels.getDocumentElement().getNamespaceURI(), "Relationship");
    rel.setAttribute("Id", relId);
    rel.setAttribute("Target", path.substring(3));
    rel.setAttribute(
        "Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet");
    rels.getDocumentElement().appendChild(rel);
    zip.change("xl/_rels/workbook.xml.rels", rels);
    Document types = zip.xml("[Content_Types].xml");
    Element type = types.createElementNS(types.getDocumentElement().getNamespaceURI(), "Override");
    type.setAttribute("PartName", "/" + path);
    type.setAttribute(
        "ContentType", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml");
    types.getDocumentElement().appendChild(type);
    zip.change("[Content_Types].xml", types);
    names.add(name);
    paths.add(path);
  }

  public int replace(String find, String replacement) throws Exception {
    if (find.isEmpty()) throw new IllegalArgumentException("찾을 내용을 입력하세요.");
    int count = 0;
    for (int i = 0; i < names.size(); i++)
      for (String address : addresses(i)) {
        String raw = value(i, address);
        if (!raw.startsWith("=") && raw.contains(find)) {
          set(i, address, raw.replace(find, replacement));
          count++;
        }
      }
    return count;
  }

  public DocumentStyle style(int index, String address) throws Exception {
    DocumentStyle style = new DocumentStyle();
    if (xls != null) {
      int[] c = coordinate(address);
      jxl.write.WritableSheet sheet = writableXls.getSheet(index);
      if (c[0] >= sheet.getColumns() || c[1] >= sheet.getRows()) return style;
      jxl.format.CellFormat format = sheet.getCell(c[0], c[1]).getCellFormat();
      if (format != null) {
        style.font = format.getFont().getName();
        style.size = format.getFont().getPointSize();
        style.bold = format.getFont().getBoldWeight() > 400;
        style.alignment =
            format.getAlignment() == jxl.format.Alignment.CENTRE
                ? "center"
                : format.getAlignment() == jxl.format.Alignment.RIGHT ? "right" : "left";
        style.numberFormat = classifyFormat(format.getFormat().getFormatString());
      }
      return style;
    }
    sheet(index);
    Element cell = cellMaps.get(index).get(address.toUpperCase(Locale.ROOT));
    if (cell == null || !zip.entries.containsKey("xl/styles.xml")) return style;
    Document xml;
    try {
      xml = zip.xml("xl/styles.xml");
    } catch (Exception invalidStyle) {
      return style;
    }
    List<Element> formats =
        ZipXml.children(ZipXml.child(xml.getDocumentElement(), "cellXfs"), "xf");
    int id = cell.hasAttribute("s") ? Integer.parseInt(cell.getAttribute("s")) : 0;
    if (id >= formats.size()) return style;
    Element xf = formats.get(id);
    Element fonts = ZipXml.child(xml.getDocumentElement(), "fonts");
    int fontId = xf.hasAttribute("fontId") ? Integer.parseInt(xf.getAttribute("fontId")) : 0;
    List<Element> fontList = ZipXml.children(fonts, "font");
    if (fontId < fontList.size()) {
      Element font = fontList.get(fontId),
          size = ZipXml.child(font, "sz"),
          face = ZipXml.child(font, "name");
      if (size != null) style.size = Float.parseFloat(size.getAttribute("val"));
      if (face != null) style.font = face.getAttribute("val");
      Element b = ZipXml.child(font, "b");
      style.bold = b != null && !Arrays.asList("0", "false").contains(b.getAttribute("val"));
    }
    Element alignment = ZipXml.child(xf, "alignment");
    if (alignment != null
        && Arrays.asList("left", "center", "right").contains(alignment.getAttribute("horizontal")))
      style.alignment = alignment.getAttribute("horizontal");
    int format = xf.hasAttribute("numFmtId") ? Integer.parseInt(xf.getAttribute("numFmtId")) : 0;
    if (format == 14 || format >= 15 && format <= 22) style.numberFormat = "date";
    else if (format == 9 || format == 10) style.numberFormat = "percent";
    else if (format >= 5 && format <= 8 || format >= 37 && format <= 44)
      style.numberFormat = "currency";
    else if (format >= 1 && format <= 4) style.numberFormat = "number";
    Element custom = ZipXml.child(xml.getDocumentElement(), "numFmts");
    if (custom != null)
      for (Element item : ZipXml.children(custom, "numFmt"))
        if (item.getAttribute("numFmtId").equals("" + format))
          style.numberFormat = classifyFormat(item.getAttribute("formatCode"));
    return style;
  }

  private static String classifyFormat(String code) {
    String f = code.toLowerCase(Locale.ROOT);
    if (f.contains("yy") || f.contains("dd")) return "date";
    if (f.contains("%")) return "percent";
    if ((f.contains("$") || f.contains("₩"))) return "currency";
    if (f.contains("0")) return "number";
    return "general";
  }

  public void format(int index, String address, DocumentStyle style) throws Exception {
    style.validate();
    if (xls != null) {
      int[] pos = coordinate(address);
      if (pos[0] > 255 || pos[1] > 65535) throw new IOException("XLS 범위를 벗어났습니다.");
      jxl.write.WritableSheet sheet = writableXls.getSheet(index);
      if (sheet.isProtected()) throw new IOException("보호된 시트입니다.");
      jxl.write.WritableCell cell = sheet.getWritableCell(pos[0], pos[1]);
      if (cell.getType() == jxl.CellType.EMPTY) {
        sheet.addCell(new jxl.write.Label(pos[0], pos[1], ""));
        cell = sheet.getWritableCell(pos[0], pos[1]);
      }
      jxl.write.WritableCellFormat format =
          cell.getCellFormat() == null
              ? new jxl.write.WritableCellFormat()
              : new jxl.write.WritableCellFormat(cell.getCellFormat());
      format.setFont(
          new jxl.write.WritableFont(
              jxl.write.WritableFont.createFont(style.font),
              Math.round(style.size),
              style.bold ? jxl.write.WritableFont.BOLD : jxl.write.WritableFont.NO_BOLD));
      format.setAlignment(
          style.alignment.equals("center")
              ? jxl.format.Alignment.CENTRE
              : style.alignment.equals("right")
                  ? jxl.format.Alignment.RIGHT
                  : jxl.format.Alignment.LEFT);
      if (!style.numberFormat.equals("general")) {
        jxl.write.WritableCellFormat number =
            new jxl.write.WritableCellFormat(
                new jxl.write.WritableFont(format.getFont()),
                new jxl.write.NumberFormat(formatCode(style.numberFormat)));
        number.setAlignment(format.getAlignment());
        format = number;
      }
      cell.setCellFormat(format);
      return;
    }
    sheet(index);
    Element cell = cellMaps.get(index).get(address.toUpperCase(Locale.ROOT));
    if (cell == null) {
      set(index, address, "");
      cell = cellMaps.get(index).get(address.toUpperCase(Locale.ROOT));
    }
    Document styles;
    if (zip.entries.containsKey("xl/styles.xml")) styles = zip.xml("xl/styles.xml");
    else {
      String initial =
          "<styleSheet xmlns='"
              + NS
              + "'><fonts count='1'><font><sz val='11'/><name val='Calibri'/></font></fonts><fills"
              + " count='2'><fill><patternFill patternType='none'/></fill><fill><patternFill"
              + " patternType='gray125'/></fill></fills><borders"
              + " count='1'><border/></borders><cellStyleXfs count='1'><xf numFmtId='0' fontId='0'"
              + " fillId='0' borderId='0'/></cellStyleXfs><cellXfs count='1'><xf numFmtId='0'"
              + " fontId='0' fillId='0' borderId='0' xfId='0'/></cellXfs></styleSheet>";
      zip.entries.put("xl/styles.xml", initial.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      styles = zip.xml("xl/styles.xml");
      Document rels = zip.xml("xl/_rels/workbook.xml.rels");
      Element rel =
          rels.createElementNS(rels.getDocumentElement().getNamespaceURI(), "Relationship");
      rel.setAttribute("Id", "rStyle" + UUID.randomUUID().toString().replace("-", ""));
      rel.setAttribute("Target", "styles.xml");
      rel.setAttribute(
          "Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles");
      rels.getDocumentElement().appendChild(rel);
      zip.change("xl/_rels/workbook.xml.rels", rels);
      Document types = zip.xml("[Content_Types].xml");
      Element override =
          types.createElementNS(types.getDocumentElement().getNamespaceURI(), "Override");
      override.setAttribute("PartName", "/xl/styles.xml");
      override.setAttribute(
          "ContentType", "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml");
      types.getDocumentElement().appendChild(override);
      zip.change("[Content_Types].xml", types);
    }
    Element root = styles.getDocumentElement(),
        fonts = ZipXml.child(root, "fonts"),
        xfs = ZipXml.child(root, "cellXfs");
    if (fonts == null || xfs == null) throw new IOException("셀 서식 정보가 올바르지 않습니다.");
    List<Element> old = ZipXml.children(xfs, "xf");
    int oldId = cell.hasAttribute("s") ? Integer.parseInt(cell.getAttribute("s")) : 0;
    Element xf =
        oldId < old.size()
            ? (Element) old.get(oldId).cloneNode(true)
            : styles.createElementNS(NS, "xf");
    List<Element> fontList = ZipXml.children(fonts, "font");
    int oldFont = xf.hasAttribute("fontId") ? Integer.parseInt(xf.getAttribute("fontId")) : 0;
    Element font =
        oldFont < fontList.size()
            ? (Element) fontList.get(oldFont).cloneNode(true)
            : styles.createElementNS(NS, "font");
    attributeElement(styles, font, "name", "val", style.font);
    attributeElement(styles, font, "sz", "val", "" + style.size);
    Element bold = ZipXml.child(font, "b");
    if (bold != null) font.removeChild(bold);
    if (style.bold) font.appendChild(styles.createElementNS(NS, "b"));
    xf.setAttribute("fontId", "" + fontList.size());
    fonts.appendChild(font);
    fonts.setAttribute("count", "" + (fontList.size() + 1));
    xf.setAttribute("applyFont", "1");
    Element alignment = ZipXml.child(xf, "alignment");
    if (alignment == null) {
      alignment = styles.createElementNS(NS, "alignment");
      xf.appendChild(alignment);
    }
    alignment.setAttribute("horizontal", style.alignment);
    xf.setAttribute("applyAlignment", "1");
    int formatId = 0;
    if (!style.numberFormat.equals("general")) {
      Element formats = ZipXml.child(root, "numFmts");
      if (formats == null) {
        formats = styles.createElementNS(NS, "numFmts");
        root.insertBefore(formats, root.getFirstChild());
      }
      formatId = 164;
      for (Element f : ZipXml.children(formats, "numFmt"))
        formatId = Math.max(formatId, Integer.parseInt(f.getAttribute("numFmtId")) + 1);
      Element f = styles.createElementNS(NS, "numFmt");
      f.setAttribute("numFmtId", "" + formatId);
      f.setAttribute("formatCode", formatCode(style.numberFormat));
      formats.appendChild(f);
      formats.setAttribute("count", "" + ZipXml.children(formats, "numFmt").size());
    }
    xf.setAttribute("numFmtId", "" + formatId);
    xf.setAttribute("applyNumberFormat", "1");
    xfs.appendChild(xf);
    xfs.setAttribute("count", "" + (old.size() + 1));
    cell.setAttribute("s", "" + old.size());
    zip.change("xl/styles.xml", styles);
    zip.change(paths.get(index), sheets.get(index));
  }

  private static String formatCode(String type) {
    return type.equals("date")
        ? "yyyy-mm-dd"
        : type.equals("percent") ? "0.00%" : type.equals("currency") ? "\"₩\"#,##0.00" : "#,##0.##";
  }

  private static void attributeElement(
      Document xml, Element parent, String tag, String name, String value) {
    Element e = ZipXml.child(parent, tag);
    if (e == null) {
      e = xml.createElementNS(NS, tag);
      parent.appendChild(e);
    }
    e.setAttribute(name, value);
  }

  private static boolean numeric(String value) {
    if (!value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")) return false;
    try {
      return new BigDecimal(value).precision() <= 15 && Double.isFinite(Double.parseDouble(value));
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static String textRuns(Element element) {
    StringBuilder out = new StringBuilder();
    NodeList list = element.getElementsByTagNameNS("*", "t");
    for (int i = 0; i < list.getLength(); i++) out.append(list.item(i).getTextContent());
    return out.toString();
  }

  public static int[] coordinate(String address) {
    String upper = address.toUpperCase(Locale.ROOT).replace("$", "");
    if (!upper.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))
      throw new IllegalArgumentException("셀 주소를 A1 형식으로 입력하세요.");
    int col = 0, i = 0;
    while (i < upper.length() && Character.isLetter(upper.charAt(i)))
      col = col * 26 + upper.charAt(i++) - 'A' + 1;
    int row = Integer.parseInt(upper.substring(i));
    if (col > 16384 || row > 1048576) throw new IllegalArgumentException("Excel 셀 범위를 벗어났습니다.");
    return new int[] {col - 1, row - 1};
  }

  public static String address(int column, int row) {
    StringBuilder text = new StringBuilder();
    int col = column + 1;
    while (col > 0) {
      text.insert(0, (char) ('A' + (col - 1) % 26));
      col = (col - 1) / 26;
    }
    return text.append(row + 1).toString();
  }

  private static boolean inRange(String address, String range) {
    if (range.isEmpty()) return false;
    String[] ends = range.split(":");
    int[] p = coordinate(address), a = coordinate(ends[0]), b = coordinate(ends[ends.length - 1]);
    return p[0] >= a[0] && p[0] <= b[0] && p[1] >= a[1] && p[1] <= b[1];
  }

  public void save(File file) throws Exception {
    if (xls != null) {
      writableXls.write();
      writableXls.close();
      writableXls = null;
      copy(xlsTemp, file);
      xls.close();
      xls = jxl.Workbook.getWorkbook(file);
      writableXls = jxl.Workbook.createWorkbook(xlsTemp, xls);
      return;
    }
    Document workbook = zip.xml("xl/workbook.xml");
    Element calc = ZipXml.child(workbook.getDocumentElement(), "calcPr");
    if (calc == null) {
      calc = workbook.createElementNS(NS, "calcPr");
      workbook.getDocumentElement().appendChild(calc);
    }
    calc.setAttribute("fullCalcOnLoad", "1");
    calc.setAttribute("forceFullCalc", "1");
    calc.setAttribute("calcMode", "auto");
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

  private static void removeMatching(
      Document document, String tag, String attribute, String ending) {
    NodeList list = document.getElementsByTagNameNS("*", tag);
    for (int i = list.getLength() - 1; i >= 0; i--) {
      Element node = (Element) list.item(i);
      if (node.getAttribute(attribute).endsWith(ending)) node.getParentNode().removeChild(node);
    }
  }

  private static void copy(File from, File to) throws IOException {
    try (InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to)) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
  }

  public void close() {
    if (writableXls != null)
      try {
        writableXls.close();
      } catch (Exception ignored) {
      }
    if (xls != null) xls.close();
    if (xlsTemp != null) xlsTemp.delete();
  }
}
