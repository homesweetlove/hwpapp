package com.homesweetlove.hwpapp.core;

import java.io.*;
import java.util.*;
import org.w3c.dom.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.table.*;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.writer.HWPWriter;

/** Text-run editor. Extended controls are never flattened or replaced. */
public final class HangulDocument {
    public final List<TextBlock> blocks = new ArrayList<>();
    private HWPFile hwp;
    private ZipXml hwpx;
    public HangulDocument(File file, boolean isHwpx) throws Exception {
        if (isHwpx) {
            hwpx = new ZipXml(file);
            List<String> sections = new ArrayList<>();
            for (String name : hwpx.entries.keySet()) if (name.matches("Contents/section[0-9]+\\.xml")) sections.add(name);
            sections.sort(Comparator.comparingInt(s -> Integer.parseInt(s.replaceAll("\\D", ""))));
            if (sections.isEmpty()) throw new IOException("HWPX 본문을 찾을 수 없습니다.");
            for (String name : sections) {
                Document xml = hwpx.xml(name);
                NodeList texts = xml.getElementsByTagNameNS("*", "t");
                for (int i = 0; i < texts.getLength(); i++) {
                    Element node = (Element) texts.item(i);
                    boolean simple = true;
                    for (Node c = node.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) simple = false;
                    final boolean editable = simple;
                    blocks.add(new TextBlock("본문 " + (blocks.size() + 1), node.getTextContent(), editable, value -> {
                        node.setTextContent(value); hwpx.change(name, xml);
                    }));
                }
            }
        } else {
            hwp = HWPReader.fromFile(file);
            if (hwp == null) throw new IOException("HWP 5 문서를 읽을 수 없습니다. 암호화·배포용 문서는 지원하지 않습니다.");
            if (hwp.getFileHeader().isDistribution()) throw new IOException("배포용 문서는 지원하지 않습니다.");
            int sectionIndex = 0;
            for (Section section : hwp.getBodyText().getSectionList()) {
                sectionIndex++;
                for (Paragraph paragraph : section) addParagraph(paragraph, "구역 " + sectionIndex, 0);
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
                if (!normal(chars.get(i))) { i++; continue; }
                int start = i;
                StringBuilder text = new StringBuilder();
                while (i < chars.size() && normal(chars.get(i))) text.append((char) chars.get(i++).getCode());
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
                    row++; int col = 0;
                    for (Cell cell : r.getCellList()) {
                        col++;
                        for (Paragraph inner : cell.getParagraphList()) addParagraph(inner, location + " / 표 " + row + "행 " + col + "열", depth + 1);
                    }
                }
            } else {
                ParagraphList list = null;
                if (control instanceof ControlHeader) list = ((ControlHeader) control).getParagraphList();
                else if (control instanceof ControlFooter) list = ((ControlFooter) control).getParagraphList();
                else if (control instanceof ControlFootnote) list = ((ControlFootnote) control).getParagraphList();
                else if (control instanceof ControlEndnote) list = ((ControlEndnote) control).getParagraphList();
                if (list != null) for (Paragraph inner : list) addParagraph(inner, location + " / 머리말·주석", depth + 1);
            }
        }
    }
    private void addRun(Paragraph p, String location, ArrayList<HWPChar> chars, int start, int runLength, String text) {
                final HWPChar first = runLength == 0 ? null : chars.get(start);
                final int[] currentLength = {runLength};
                final HWPChar[] anchor = {first};
                final HWPChar next = start + runLength < chars.size() ? chars.get(start + runLength) : null;
                boolean editable = p.getRangeTag() == null || p.getRangeTag().getRangeTagItemList().isEmpty();
                blocks.add(new TextBlock(location + " · 텍스트 " + (blocks.size() + 1), text, editable, value -> {
                    int from = anchor[0] == null ? (next == null ? chars.size() : chars.indexOf(next)) : chars.indexOf(anchor[0]);
                    if (from < 0) throw new IOException("문단 위치가 변경되었습니다.");
                    int offset = 0;
                    for (int c = 0; c < from; c++) offset += chars.get(c).getCharSize();
                    int oldLength = currentLength[0];
                    for (int c = 0; c < oldLength; c++) chars.remove(from);
                    for (int c = 0; c < value.length(); c++) chars.add(from + c, new HWPCharNormal(value.charAt(c)));
                    if (p.getCharShape() != null) {
                        List<CharPositionShapeIdPair> pairs = p.getCharShape().getPositonShapeIdPairList();
                        final int end = offset + oldLength;
                        final int begin = offset;
                        // Existing styles before the edit remain; inserted text inherits the run's first style.
                        pairs.removeIf(pair -> pair.getPosition() > begin && pair.getPosition() < end);
                        for (CharPositionShapeIdPair pair : pairs) if (pair.getPosition() >= end && pair.getPosition() > begin)
                            pair.setPosition(pair.getPosition() + value.length() - oldLength);
                        p.getHeader().setCharShapeCount(pairs.size());
                    }
                    currentLength[0] = value.length();
                    anchor[0] = value.isEmpty() ? null : chars.get(from);
                    p.getHeader().setCharacterCount(p.getText().getCharSize());
                    p.deleteLineSeg(); p.getHeader().setLineAlignCount(0);
                }));
    }
    private static boolean normal(HWPChar c) { return c.getType() == HWPCharType.Normal && c.getCode() >= 32; }
    public void save(File file) throws Exception {
        if (hwpx != null) hwpx.save(file); else HWPWriter.toFile(hwp, file.getAbsolutePath());
    }
}
