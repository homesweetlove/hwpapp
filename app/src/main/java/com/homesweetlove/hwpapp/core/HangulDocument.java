package com.homesweetlove.hwpapp.core;

import java.io.*;
import java.util.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.table.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.*;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.w3c.dom.*;

/** Text-run editor. Extended controls are never flattened or replaced. */
public final class HangulDocument {
  public final List<TextBlock> blocks = new ArrayList<>();
  private HWPFile hwp;
  private ZipXml hwpx;
  private final Map<TextBlock, Paragraph> paragraphs = new IdentityHashMap<>();
  private final Map<Paragraph, kr.dogfoot.hwplib.object.bodytext.ParagraphListInterface> owners =
      new IdentityHashMap<>();
  private final Map<TextBlock, Element> xmlTexts = new IdentityHashMap<>();
  private final Map<Element, String> xmlPaths = new IdentityHashMap<>();
  private final Map<String, Document> sections = new LinkedHashMap<>();

  public HWPFile binaryModel() {
    return hwp;
  }

  public ZipXml packageModel() {
    return hwpx;
  }

  public HangulDocument(File file, boolean isHwpx) throws Exception {
    if (isHwpx) {
      hwpx = new ZipXml(file);
      List<String> sections = new ArrayList<>();
      for (String name : hwpx.entries.keySet())
        if (name.matches("Contents/section[0-9]+\\.xml")) sections.add(name);
      sections.sort(Comparator.comparingInt(s -> Integer.parseInt(s.replaceAll("\\D", ""))));
      if (sections.isEmpty()) throw new IOException("HWPX 본문을 찾을 수 없습니다.");
      for (String name : sections) {
        Document xml = hwpx.xml(name);
        this.sections.put(name, xml);
        NodeList texts = xml.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < texts.getLength(); i++) {
          Element node = (Element) texts.item(i);
          boolean simple = true;
          for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element) simple = false;
          final boolean editable = simple;
          TextBlock block =
              new TextBlock(
                  "본문 " + (blocks.size() + 1),
                  node.getTextContent(),
                  editable,
                  value -> {
                    node.setTextContent(value);
                    hwpx.change(name, xml);
                  });
          blocks.add(block);
          xmlTexts.put(block, node);
          xmlPaths.put(node, name);
        }
      }
    } else {
      hwp = HWPReader.fromFile(file);
      if (hwp == null) throw new IOException("HWP 5 문서를 읽을 수 없습니다. 암호화·배포용 문서는 지원하지 않습니다.");
      if (hwp.getFileHeader().isDistribution()) throw new IOException("배포용 문서는 지원하지 않습니다.");
      int sectionIndex = 0;
      for (Section section : hwp.getBodyText().getSectionList()) {
        sectionIndex++;
        for (Paragraph paragraph : section) {
          owners.put(paragraph, section);
          addParagraph(paragraph, "구역 " + sectionIndex, 0);
        }
      }
    }
    if (blocks.size() > 20000) throw new IOException("문단이 너무 많습니다.");
  }

  private void addParagraph(Paragraph p, String location, int depth) throws Exception {
    if (depth > 30 || blocks.size() > 20000) throw new IOException("문서 구조가 너무 큽니다.");
    if (p.getText() != null) {
      ArrayList<HWPChar> chars = p.getText().getCharList();
      int i = 0;
      while (i < chars.size()) {
        if (!normal(chars.get(i))) {
          i++;
          continue;
        }
        int start = i;
        StringBuilder text = new StringBuilder();
        while (i < chars.size() && normal(chars.get(i)))
          text.append((char) chars.get(i++).getCode());
        addRun(p, location, chars, start, i - start, text.toString());
      }
      if (chars.stream().noneMatch(HangulDocument::normal)) {
        int end = chars.size();
        if (end > 0 && chars.get(end - 1).getCode() == 13) end--;
        addRun(p, location, chars, end, 0, "");
      }
    }
    if (p.getControlList() == null) return;
    for (Control control : p.getControlList()) {
      if (control instanceof ControlTable) {
        int row = 0;
        for (Row r : ((ControlTable) control).getRowList()) {
          row++;
          int col = 0;
          for (Cell cell : r.getCellList()) {
            col++;
            for (Paragraph inner : cell.getParagraphList()) {
              owners.put(inner, cell.getParagraphList());
              addParagraph(inner, location + " / 표 " + row + "행 " + col + "열", depth + 1);
            }
          }
        }
      } else {
        ParagraphList list = null;
        if (control instanceof ControlHeader) list = ((ControlHeader) control).getParagraphList();
        else if (control instanceof ControlFooter)
          list = ((ControlFooter) control).getParagraphList();
        else if (control instanceof ControlFootnote)
          list = ((ControlFootnote) control).getParagraphList();
        else if (control instanceof ControlEndnote)
          list = ((ControlEndnote) control).getParagraphList();
        if (list != null)
          for (Paragraph inner : list) {
            owners.put(inner, list);
            addParagraph(inner, location + " / 머리말·주석", depth + 1);
          }
      }
    }
  }

  private void addRun(
      Paragraph p,
      String location,
      ArrayList<HWPChar> chars,
      int start,
      int runLength,
      String text) {
    final HWPChar first = runLength == 0 ? null : chars.get(start);
    final int[] currentLength = {runLength};
    final HWPChar[] anchor = {first};
    final HWPChar next = start + runLength < chars.size() ? chars.get(start + runLength) : null;
    boolean editable = p.getRangeTag() == null || p.getRangeTag().getRangeTagItemList().isEmpty();
    TextBlock block =
        new TextBlock(
            location + " · 텍스트 " + (blocks.size() + 1),
            text,
            editable,
            value -> {
              int from =
                  anchor[0] == null
                      ? (next == null ? chars.size() : chars.indexOf(next))
                      : chars.indexOf(anchor[0]);
              if (from < 0) throw new IOException("문단 위치가 변경되었습니다.");
              int offset = 0;
              for (int c = 0; c < from; c++) offset += chars.get(c).getCharSize();
              int oldLength = currentLength[0];
              // Preserve unchanged prefix/suffix and their mixed character styles.
              int prefix = 0, suffix = 0;
              while (prefix < oldLength
                  && prefix < value.length()
                  && chars.get(from + prefix).getCode() == value.charAt(prefix)) prefix++;
              while (suffix < oldLength - prefix
                  && suffix < value.length() - prefix
                  && chars.get(from + oldLength - 1 - suffix).getCode()
                      == value.charAt(value.length() - 1 - suffix)) suffix++;
              int removed = oldLength - prefix - suffix,
                  inserted = value.length() - prefix - suffix;
              for (int c = 0; c < removed; c++) chars.remove(from + prefix);
              for (int c = 0; c < inserted; c++)
                chars.add(from + prefix + c, new HWPCharNormal(value.charAt(prefix + c)));
              if (p.getCharShape() != null) {
                List<CharPositionShapeIdPair> pairs = p.getCharShape().getPositonShapeIdPairList();
                final int end = offset + oldLength - suffix;
                final int begin = offset + prefix;
                long suffixStyle = 0;
                for (CharPositionShapeIdPair pair : pairs)
                  if (pair.getPosition() <= end) suffixStyle = pair.getShapeId();
                pairs.removeIf(pair -> pair.getPosition() > begin && pair.getPosition() < end);
                for (CharPositionShapeIdPair pair : pairs)
                  if (pair.getPosition() >= end && pair.getPosition() > begin)
                    pair.setPosition(pair.getPosition() + value.length() - oldLength);
                if (suffix > 0 && removed > 0) {
                  long newEnd = begin + inserted;
                  boolean exists = false;
                  for (CharPositionShapeIdPair pair : pairs)
                    if (pair.getPosition() == newEnd) {
                      pair.setShapeId(suffixStyle);
                      exists = true;
                    }
                  if (!exists) p.getCharShape().addParaCharShape(newEnd, suffixStyle);
                  pairs.sort(Comparator.comparingLong(CharPositionShapeIdPair::getPosition));
                }
                for (int n = pairs.size() - 1; n > 0; n--)
                  if (pairs.get(n).getPosition() == pairs.get(n - 1).getPosition())
                    pairs.remove(n - 1);
                p.getHeader().setCharShapeCount(pairs.size());
              }
              currentLength[0] = value.length();
              anchor[0] = value.isEmpty() ? null : chars.get(from);
              p.getHeader().setCharacterCount(p.getText().getCharSize());
              p.deleteLineSeg();
              p.getHeader().setLineAlignCount(0);
            });
    blocks.add(block);
    paragraphs.put(block, p);
  }

  public int replace(String find, String replacement) throws Exception {
    if (find.isEmpty()) throw new IllegalArgumentException("찾을 내용을 입력하세요.");
    int count = 0;
    for (TextBlock block : blocks)
      if (block.editable && block.text.contains(find)) {
        block.set(block.text.replace(find, replacement));
        count++;
      }
    return count;
  }

  public DocumentStyle style(TextBlock block) throws Exception {
    DocumentStyle style = new DocumentStyle();
    if (hwp != null) {
      Paragraph p = paragraphs.get(block);
      if (p.getCharShape() != null && !p.getCharShape().getPositonShapeIdPairList().isEmpty()) {
        int id = (int) p.getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
        kr.dogfoot.hwplib.object.docinfo.CharShape shape =
            hwp.getDocInfo().getCharShapeList().get(id);
        style.size = shape.getBaseSize() / 100f;
        style.bold = shape.getProperty().isBold();
        int face = shape.getFaceNameIds().getHangul();
        if (face < hwp.getDocInfo().getHangulFaceNameList().size())
          style.font = hwp.getDocInfo().getHangulFaceNameList().get(face).getName();
      }
      kr.dogfoot.hwplib.object.docinfo.ParaShape ps =
          hwp.getDocInfo().getParaShapeList().get(p.getHeader().getParaShapeId());
      style.alignment =
          ps.getProperty1().getAlignment()
                  == kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Center
              ? "center"
              : ps.getProperty1().getAlignment()
                      == kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Right
                  ? "right"
                  : "left";
    } else if (hwpx.entries.containsKey("Contents/header.xml")) {
      Element text = xmlTexts.get(block), run = ancestor(text, "run");
      Document header = hwpx.xml("Contents/header.xml");
      Element cp = byId(header, "charPr", run == null ? "0" : run.getAttribute("charPrIDRef"));
      if (cp != null) {
        style.size = Float.parseFloat(cp.getAttribute("height")) / 100f;
        style.bold = ZipXml.child(cp, "bold") != null;
      }
      Element paragraph = ancestor(text, "p"),
          ps = byId(header, "paraPr", paragraph.getAttribute("paraPrIDRef"));
      Element align = ps == null ? null : ZipXml.child(ps, "align");
      if (align != null)
        style.alignment =
            align.getAttribute("horizontal").equals("CENTER")
                ? "center"
                : align.getAttribute("horizontal").equals("RIGHT") ? "right" : "left";
      Element ref = cp == null ? null : ZipXml.child(cp, "fontRef");
      NodeList faces = header.getElementsByTagNameNS("*", "fontface");
      if (ref != null)
        for (int i = 0; i < faces.getLength(); i++) {
          Element face = (Element) faces.item(i);
          if (face.getAttribute("lang").equals("HANGUL"))
            for (Element f : ZipXml.children(face, "font"))
              if (f.getAttribute("id").equals(ref.getAttribute("hangul")))
                style.font = f.getAttribute("face");
        }
    }
    return style;
  }

  public void format(TextBlock block, DocumentStyle style) throws Exception {
    style.validate();
    if (!block.editable) throw new IOException("읽기 전용 텍스트입니다.");
    if (hwp != null) {
      Paragraph p = paragraphs.get(block);
      kr.dogfoot.hwplib.object.docinfo.DocInfo info = hwp.getDocInfo();
      int current =
          p.getCharShape() != null && !p.getCharShape().getPositonShapeIdPairList().isEmpty()
              ? (int) p.getCharShape().getPositonShapeIdPairList().get(0).getShapeId()
              : 0;
      kr.dogfoot.hwplib.object.docinfo.CharShape shape =
          info.getCharShapeList().get(current).clone();
      shape.setBaseSize(Math.round(style.size * 100));
      shape.getProperty().setBold(style.bold);
      List<List<kr.dogfoot.hwplib.object.docinfo.FaceName>> lists =
          Arrays.asList(
              info.getHangulFaceNameList(),
              info.getEnglishFaceNameList(),
              info.getHanjaFaceNameList(),
              info.getJapaneseFaceNameList(),
              info.getEtcFaceNameList(),
              info.getSymbolFaceNameList(),
              info.getUserFaceNameList());
      int[] ids = new int[7];
      for (int i = 0; i < 7; i++) {
        List<kr.dogfoot.hwplib.object.docinfo.FaceName> list = lists.get(i);
        int id = -1;
        for (int j = 0; j < list.size(); j++) if (list.get(j).getName().equals(style.font)) id = j;
        if (id < 0) {
          kr.dogfoot.hwplib.object.docinfo.FaceName face =
              new kr.dogfoot.hwplib.object.docinfo.FaceName();
          face.setName(style.font);
          id = list.size();
          list.add(face);
        }
        ids[i] = id;
      }
      shape.getFaceNameIds().setArray(ids);
      int id = info.getCharShapeList().size();
      info.getCharShapeList().add(shape);
      if (p.getCharShape() == null) p.createCharShape();
      List<CharPositionShapeIdPair> pairs = p.getCharShape().getPositonShapeIdPairList();
      if (pairs.isEmpty()) p.getCharShape().addParaCharShape(0, id);
      else {
        Map<Long, Integer> replacements = new HashMap<>();
        for (CharPositionShapeIdPair pair : pairs) {
          long oldId = pair.getShapeId();
          Integer replacement = replacements.get(oldId);
          if (replacement == null) {
            kr.dogfoot.hwplib.object.docinfo.CharShape styled =
                info.getCharShapeList().get((int) oldId).clone();
            styled.setBaseSize(Math.round(style.size * 100));
            styled.getProperty().setBold(style.bold);
            styled.getFaceNameIds().setArray(ids);
            replacement = info.getCharShapeList().size();
            info.getCharShapeList().add(styled);
            replacements.put(oldId, replacement);
          }
          pair.setShapeId(replacement);
        }
      }
      p.getHeader().setCharShapeCount(p.getCharShape().getPositonShapeIdPairList().size());
      kr.dogfoot.hwplib.object.docinfo.ParaShape ps =
          info.getParaShapeList().get(p.getHeader().getParaShapeId()).clone();
      ps.getProperty1()
          .setAlignment(
              style.alignment.equals("center")
                  ? kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Center
                  : style.alignment.equals("right")
                      ? kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Right
                      : kr.dogfoot.hwplib.object.docinfo.parashape.Alignment.Left);
      p.getHeader().setParaShapeId(info.getParaShapeList().size());
      info.getParaShapeList().add(ps);
      p.deleteLineSeg();
      p.getHeader().setLineAlignCount(0);
    } else {
      if (!hwpx.entries.containsKey("Contents/header.xml"))
        throw new IOException("HWPX 서식 정보가 없습니다.");
      Document header = hwpx.xml("Contents/header.xml");
      Element text = xmlTexts.get(block),
          run = ancestor(text, "run"),
          paragraph = ancestor(text, "p");
      Element cp = cloneRecord(header, "charPr", run.getAttribute("charPrIDRef"));
      cp.setAttribute("height", "" + Math.round(style.size * 100));
      Element bold = ZipXml.child(cp, "bold");
      if (bold != null) cp.removeChild(bold);
      if (style.bold) cp.appendChild(header.createElementNS(cp.getNamespaceURI(), "hh:bold"));
      Element ref = ZipXml.child(cp, "fontRef");
      NodeList faces = header.getElementsByTagNameNS("*", "fontface");
      for (int i = 0; i < faces.getLength(); i++) {
        Element face = (Element) faces.item(i);
        String lang = face.getAttribute("lang").toLowerCase(Locale.ROOT);
        if (lang.equals("latin")) lang = "latin";
        if (lang.equals("user")) lang = "user";
        List<Element> fonts = ZipXml.children(face, "font");
        int id = 0;
        for (Element f : fonts) id = Math.max(id, Integer.parseInt(f.getAttribute("id")) + 1);
        Element font =
            fonts.isEmpty()
                ? header.createElementNS(face.getNamespaceURI(), "hh:font")
                : (Element) fonts.get(0).cloneNode(true);
        font.setAttribute("id", "" + id);
        font.setAttribute("face", style.font);
        font.setAttribute("isEmbedded", "0");
        face.appendChild(font);
        face.setAttribute("fontCnt", "" + (fonts.size() + 1));
        if (ref != null) ref.setAttribute(lang, "" + id);
      }
      run.setAttribute("charPrIDRef", cp.getAttribute("id"));
      Element ps = cloneRecord(header, "paraPr", paragraph.getAttribute("paraPrIDRef"));
      Element align = ZipXml.child(ps, "align");
      if (align == null) {
        align = header.createElementNS(ps.getNamespaceURI(), "hh:align");
        ps.insertBefore(align, ps.getFirstChild());
      }
      align.setAttribute("horizontal", style.alignment.toUpperCase(Locale.ROOT));
      paragraph.setAttribute("paraPrIDRef", ps.getAttribute("id"));
      hwpx.change("Contents/header.xml", header);
      hwpx.change(xmlPaths.get(text), sections.get(xmlPaths.get(text)));
    }
  }

  public void insertAfter(TextBlock block, String text) throws Exception {
    TextBlock validator = new TextBlock("", "", true, value -> {});
    validator.set(text);
    if (hwp != null) {
      Paragraph p = paragraphs.get(block);
      kr.dogfoot.hwplib.object.bodytext.ParagraphListInterface list = owners.get(p);
      Paragraph next = new Paragraph();
      next.getHeader().copy(p.getHeader());
      next.getHeader().getControlMask().setValue(0);
      next.getHeader().setLastInList(false);
      next.getHeader().setRangeTagCount(0);
      next.getHeader().setLineAlignCount(0);
      next.createText();
      next.getText().addString(text);
      next.getHeader().setCharacterCount(next.getText().getCharSize());
      next.createCharShape();
      int shape =
          p.getCharShape() == null || p.getCharShape().getPositonShapeIdPairList().isEmpty()
              ? 0
              : (int) p.getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
      next.getCharShape().addParaCharShape(0, shape);
      next.getHeader().setCharShapeCount(1);
      int position = 0;
      for (Paragraph item : list) {
        position++;
        if (item == p) break;
      }
      list.insertParagraph(position, next);
      fixLast(list);
    } else {
      Element node = xmlTexts.get(block), paragraph = ancestor(node, "p");
      Element next = (Element) paragraph.cloneNode(false);
      next.setAttribute("id", "" + (System.nanoTime() & 0x7fffffff));
      next.setAttribute("pageBreak", "0");
      next.setAttribute("columnBreak", "0");
      Element run = (Element) ancestor(node, "run").cloneNode(false),
          t = (Element) node.cloneNode(false);
      t.setTextContent(text);
      run.appendChild(t);
      next.appendChild(run);
      paragraph.getParentNode().insertBefore(next, paragraph.getNextSibling());
      hwpx.change(xmlPaths.get(node), sections.get(xmlPaths.get(node)));
    }
  }

  public void deleteParagraph(TextBlock block) throws Exception {
    if (hwp != null) {
      Paragraph p = paragraphs.get(block);
      kr.dogfoot.hwplib.object.bodytext.ParagraphListInterface list = owners.get(p);
      if (list.getParagraphCount() <= 1) throw new IOException("마지막 문단은 삭제할 수 없습니다.");
      if (p.getControlList() != null && !p.getControlList().isEmpty())
        throw new IOException("표·개체가 포함된 문단은 삭제할 수 없습니다.");
      int index = 0;
      for (Paragraph item : list) {
        if (item == p) break;
        index++;
      }
      list.deleteParagraph(index);
      fixLast(list);
    } else {
      Element text = xmlTexts.get(block), paragraph = ancestor(text, "p");
      if (ZipXml.children((Element) paragraph.getParentNode(), "p").size() <= 1)
        throw new IOException("마지막 문단은 삭제할 수 없습니다.");
      for (String tag : Arrays.asList("tbl", "pic", "secPr", "ctrl"))
        if (paragraph.getElementsByTagNameNS("*", tag).getLength() > 0)
          throw new IOException("개체가 포함된 문단입니다.");
      paragraph.getParentNode().removeChild(paragraph);
      hwpx.change(xmlPaths.get(text), sections.get(xmlPaths.get(text)));
    }
  }

  private static void fixLast(kr.dogfoot.hwplib.object.bodytext.ParagraphListInterface list) {
    for (int i = 0; i < list.getParagraphCount(); i++)
      list.getParagraph(i).getHeader().setLastInList(i == list.getParagraphCount() - 1);
  }

  private static Element ancestor(Element element, String tag) {
    for (Node n = element; n instanceof Element; n = n.getParentNode())
      if (tag.equals(n.getLocalName())) return (Element) n;
    return null;
  }

  private static Element byId(Document xml, String tag, String id) {
    NodeList items = xml.getElementsByTagNameNS("*", tag);
    for (int i = 0; i < items.getLength(); i++) {
      Element e = (Element) items.item(i);
      if (e.getAttribute("id").equals(id)) return e;
    }
    return null;
  }

  private static Element cloneRecord(Document xml, String tag, String id) throws IOException {
    Element old = byId(xml, tag, id);
    if (old == null) throw new IOException("서식 항목을 찾을 수 없습니다.");
    Element parent = (Element) old.getParentNode(), record = (Element) old.cloneNode(true);
    int next = 0;
    for (Element e : ZipXml.children(parent, tag))
      next = Math.max(next, Integer.parseInt(e.getAttribute("id")) + 1);
    record.setAttribute("id", "" + next);
    parent.appendChild(record);
    parent.setAttribute("itemCnt", "" + ZipXml.children(parent, tag).size());
    return record;
  }

  private static boolean normal(HWPChar c) {
    return c.getType() == HWPCharType.Normal && c.getCode() >= 32;
  }

  public void save(File file) throws Exception {
    if (hwpx != null) hwpx.save(file);
    else HWPWriter.toFile(hwp, file.getAbsolutePath());
  }
}
