package com.homesweetlove.hwpapp;

import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.text.*;
import android.text.style.*;
import com.homesweetlove.hwpapp.core.*;
import kr.dogfoot.hwplib.object.bodytext.*;
import kr.dogfoot.hwplib.object.bodytext.control.*;
import kr.dogfoot.hwplib.object.bodytext.control.gso.*;
import kr.dogfoot.hwplib.object.bodytext.control.table.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.*;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.*;
import kr.dogfoot.hwplib.object.docinfo.*;
import org.w3c.dom.*;
import java.io.*;
import java.util.*;

/** Local compatibility layout renderer: physical paper sizes, styled paragraphs, tables and embedded images. */
final class HangulRenderer {
    private static final class Item {
        CharSequence text = "";
        DocumentStyle style = new DocumentStyle();
        final List<List<Item>> rows = new ArrayList<>();
        int col = 0, span = 1, rowSpan = 1;
        byte[] image;
        float width, height, before, after = 4;
        boolean pageBreak;
    }
    private final HangulDocument source;
    private final PdfDocument output = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas canvas;
    private float width = 595, height = 842, left = 57, right = 57, top = 57, bottom = 57, y;
    private int pageCount;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    final Set<String> notices = new LinkedHashSet<>();
    HangulRenderer(HangulDocument source) { this.source = source; }
    void export(File file) throws Exception {
        try {
            if (source.binaryModel() != null) binary(); else xml();
            finishPage(); if (pageCount == 0) { beginPage(); finishPage(); }
            try (OutputStream stream = new FileOutputStream(file)) { output.writeTo(stream); }
        } finally { output.close(); }
    }
    private void binary() throws Exception {
        for (Section section : source.binaryModel().getBodyText().getSectionList()) {
            finishPage();
            for (Paragraph p : section) if (p.getControlList() != null) for (Control c : p.getControlList()) if (c instanceof ControlSectionDefine) {
                kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PageDef def = ((ControlSectionDefine) c).getPageDef();
                width = def.getPaperWidth() / 100f; height = def.getPaperHeight() / 100f;
                if (def.getProperty().getPaperDirection() == kr.dogfoot.hwplib.object.bodytext.control.sectiondefine.PaperDirection.Landscape && height > width) { float swap = width; width = height; height = swap; }
                left = def.getLeftMargin() / 100f; right = def.getRightMargin() / 100f; top = def.getTopMargin() / 100f; bottom = def.getBottomMargin() / 100f;
            }
            validatePaper(); beginPage();
            for (Paragraph paragraph : section) for (Item item : binaryParagraph(paragraph, 0)) draw(item);
        }
    }
    private List<Item> binaryParagraph(Paragraph paragraph, int depth) throws Exception {
        if (depth > 20) throw new IOException("중첩 문서 구조가 너무 깊습니다.");
        List<Item> list = new ArrayList<>(); Item text = new Item();
        if (paragraph.getText() != null) {
            SpannableStringBuilder builder = new SpannableStringBuilder(); int position = 0;
            int spanStart = 0; DocumentStyle currentStyle = null;
            for (HWPChar ch : paragraph.getText().getCharList()) {
                DocumentStyle nextStyle = binaryStyle(paragraph, position);
                if (currentStyle != null && (nextStyle.size != currentStyle.size || nextStyle.bold != currentStyle.bold || nextStyle.color != currentStyle.color || !nextStyle.font.equals(currentStyle.font))) {
                    if (builder.length() > spanStart) apply(builder, spanStart, builder.length(), currentStyle);
                    spanStart = builder.length();
                }
                currentStyle = nextStyle;
                if (ch.getType() == HWPCharType.Normal && ch.getCode() != 13) builder.append(String.valueOf((char) ch.getCode()));
                else if (ch.getCode() == 9) builder.append("\t");
                else if (ch.getCode() == 10) builder.append("\n");
                position += ch.getCharSize();
            }
            if (builder.length() > spanStart && currentStyle != null) apply(builder, spanStart, builder.length(), currentStyle);
            text.text = builder; text.style = binaryStyle(paragraph, 0);
        }
        ParaShape ps = source.binaryModel().getDocInfo().getParaShapeList().get(paragraph.getHeader().getParaShapeId());
        text.before = Math.max(0, ps.getTopParaSpace() / 100f); text.after = Math.max(2, ps.getBottomParaSpace() / 100f);
        text.style.alignment = ps.getProperty1().getAlignment().toString().toLowerCase(Locale.ROOT);
        text.pageBreak = paragraph.getHeader().getDivideSort().isDividePage();
        if (text.text.length() > 0) list.add(text);
        if (paragraph.getControlList() != null) for (Control control : paragraph.getControlList()) {
            if (control instanceof ControlTable) {
                Item table = new Item();
                for (Row row : ((ControlTable) control).getRowList()) {
                    List<Item> cells = new ArrayList<>();
                    for (Cell cell : row.getCellList()) {
                        Item item = new Item(); item.col = cell.getListHeader().getColIndex(); item.span = Math.max(1, cell.getListHeader().getColSpan()); item.rowSpan = Math.max(1, cell.getListHeader().getRowSpan());
                        item.width = cell.getListHeader().getWidth() / 100f; item.height = cell.getListHeader().getHeight() / 100f;
                        SpannableStringBuilder builder = new SpannableStringBuilder();
                        for (Paragraph inner : cell.getParagraphList()) {
                            for (Item nested : binaryParagraph(inner, depth + 1)) { if (!nested.rows.isEmpty() || nested.image != null) notices.add("표 안의 중첩 개체는 별도 확인이 필요합니다."); builder.append(nested.text); builder.append("\n"); item.style = nested.style; }
                        }
                        item.text = builder; cells.add(item);
                    }
                    table.rows.add(cells);
                }
                list.add(table);
            } else if (control instanceof ControlPicture) {
                ControlPicture picture = (ControlPicture) control; Item image = new Item();
                int id = picture.getShapeComponentPicture().getPictureInfo().getBinItemID();
                if (id > 0 && id <= source.binaryModel().getDocInfo().getBinDataList().size()) {
                    kr.dogfoot.hwplib.object.docinfo.BinData data = source.binaryModel().getDocInfo().getBinDataList().get(id - 1);
                    String name = String.format(Locale.ROOT, "Bin%04X", data.getBinDataID());
                    for (kr.dogfoot.hwplib.object.bindata.EmbeddedBinaryData embedded : source.binaryModel().getBinData().getEmbeddedBinaryDataList()) if (embedded.getName().startsWith(name)) image.image = embedded.getData();
                }
                image.width = picture.getHeader().getWidth() / 100f; image.height = picture.getHeader().getHeight() / 100f;
                if (image.image != null) list.add(image); else notices.add("연결 이미지 또는 읽을 수 없는 이미지가 있습니다.");
            } else if (control instanceof ControlFootnote || control instanceof ControlEndnote) {
                ParagraphList note = control instanceof ControlFootnote ? ((ControlFootnote) control).getParagraphList() : ((ControlEndnote) control).getParagraphList();
                for (Paragraph p : note) list.addAll(binaryParagraph(p, depth + 1)); notices.add("각주·미주는 본문 다음에 표시됩니다.");
            } else if (control instanceof ControlHeader || control instanceof ControlFooter) {
                ParagraphList note = control instanceof ControlHeader ? ((ControlHeader) control).getParagraphList() : ((ControlFooter) control).getParagraphList();
                for (Paragraph p : note) list.addAll(binaryParagraph(p, depth + 1)); notices.add("머리말·꼬리말은 본문 흐름에 표시됩니다.");
            } else if (control instanceof GsoControl) notices.add("이미지 외의 도형은 지원하지 않습니다.");
        }
        if (list.isEmpty()) list.add(text); return list;
    }
    private DocumentStyle binaryStyle(Paragraph p, int position) {
        DocumentStyle style = new DocumentStyle(); int id = 0;
        if (p.getCharShape() != null) for (kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair pair : p.getCharShape().getPositonShapeIdPairList()) if (pair.getPosition() <= position) id = (int) pair.getShapeId();
        CharShape shape = source.binaryModel().getDocInfo().getCharShapeList().get(id); style.size = shape.getBaseSize() / 100f; style.bold = shape.getProperty().isBold(); style.color = Color.rgb(shape.getCharColor().getR(), shape.getCharColor().getG(), shape.getCharColor().getB());
        int font = shape.getFaceNameIds().getHangul(); if (font < source.binaryModel().getDocInfo().getHangulFaceNameList().size()) style.font = source.binaryModel().getDocInfo().getHangulFaceNameList().get(font).getName();
        return style;
    }
    private static void apply(SpannableStringBuilder text, int start, int end, DocumentStyle style) {
        text.setSpan(new AbsoluteSizeSpan(Math.max(6, Math.min(96, Math.round(style.size)))), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(style.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new TypefaceSpan(style.font), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(style.bold ? Typeface.BOLD : Typeface.NORMAL), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private void xml() throws Exception {
        ZipXml pack = source.packageModel();
        List<String> sections = new ArrayList<>(); for (String name : pack.entries.keySet()) if (name.matches("Contents/section[0-9]+\\.xml")) sections.add(name);
        sections.sort(Comparator.comparingInt(s -> Integer.parseInt(s.replaceAll("\\D", ""))));
        for (String path : sections) {
            finishPage(); Document document = pack.xml(path); NodeList props = document.getElementsByTagNameNS("*", "pagePr");
            if (props.getLength() > 0) {
                Element property = (Element) props.item(0); width = number(property, "width", 59500) / 100; height = number(property, "height", 84200) / 100;
                Element margin = ZipXml.child(property, "margin");
                if (margin != null) { left = number(margin, "left", 5700) / 100; right = number(margin, "right", 5700) / 100; top = number(margin, "top", 5700) / 100; bottom = number(margin, "bottom", 5700) / 100; }
            }
            validatePaper(); beginPage();
            for (Element paragraph : ZipXml.children(document.getDocumentElement(), "p")) for (Item item : xmlParagraph(paragraph, 0)) draw(item);
        }
    }
    private List<Item> xmlParagraph(Element paragraph, int depth) throws Exception {
        if (depth > 20) throw new IOException("중첩 문서 구조가 너무 깊습니다.");
        List<Item> list = new ArrayList<>(); Item text = new Item(); SpannableStringBuilder builder = new SpannableStringBuilder();
        text.pageBreak = paragraph.getAttribute("pageBreak").equals("1");
        for (Element run : ZipXml.children(paragraph, "run")) {
            DocumentStyle style = xmlStyle(run, paragraph);
            for (Node n = run.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) {
                Element e = (Element) n;
                if (e.getLocalName().equals("t")) { int start = builder.length(); builder.append(e.getTextContent()); apply(builder, start, builder.length(), style); text.style = style; }
                else if (e.getLocalName().equals("tbl")) {
                    Item table = new Item();
                    for (Element row : ZipXml.children(e, "tr")) {
                        List<Item> cells = new ArrayList<>(); int column = 0;
                        for (Element cell : ZipXml.children(row, "tc")) {
                            Item item = new Item(); Element addr = ZipXml.child(cell, "cellAddr"), span = ZipXml.child(cell, "cellSpan"), size = ZipXml.child(cell, "cellSz");
                            item.col = (int) number(addr, "colAddr", column++); item.span = Math.max(1, (int) number(span, "colSpan", 1)); item.rowSpan = Math.max(1, (int) number(span, "rowSpan", 1));
                            item.width = number(size, "width", 0) / 100; item.height = number(size, "height", 0) / 100;
                            Element sub = ZipXml.child(cell, "subList"); SpannableStringBuilder value = new SpannableStringBuilder();
                            if (sub != null) for (Element p : ZipXml.children(sub, "p")) for (Item inner : xmlParagraph(p, depth + 1)) { value.append(inner.text).append("\n"); item.style = inner.style; if (!inner.rows.isEmpty()) notices.add("중첩 표는 별도 확인이 필요합니다."); }
                            item.text = value; cells.add(item);
                        }
                        table.rows.add(cells);
                    }
                    list.add(table);
                } else if (e.getLocalName().equals("pic")) {
                    Item image = new Item(); NodeList images = e.getElementsByTagNameNS("*", "img");
                    if (images.getLength() > 0) {
                        String id = ((Element) images.item(0)).getAttribute("binaryItemIDRef");
                        if (source.packageModel().entries.containsKey("Contents/content.hpf")) {
                            NodeList items = source.packageModel().xml("Contents/content.hpf").getElementsByTagNameNS("*", "item");
                            for (int i = 0; i < items.getLength(); i++) { Element entry = (Element) items.item(i); if (entry.getAttribute("id").equals(id)) { String href = entry.getAttribute("href"); image.image = source.packageModel().entries.get(href); if (image.image == null) image.image = source.packageModel().entries.get("Contents/" + href); } }
                        }
                    }
                    Element size = ZipXml.child(e, "sz"); image.width = number(size, "width", 24000) / 100; image.height = number(size, "height", 18000) / 100;
                    if (image.image != null) list.add(image); else notices.add("읽을 수 없는 HWPX 이미지가 있습니다.");
                } else if (!Arrays.asList("secPr", "ctrl", "linesegarray").contains(e.getLocalName())) notices.add("일부 개체·수식은 지원하지 않습니다.");
            }
        }
        text.text = builder; list.add(0, text); return list;
    }
    private DocumentStyle xmlStyle(Element run, Element paragraph) throws Exception {
        DocumentStyle style = new DocumentStyle(); ZipXml pack = source.packageModel(); if (!pack.entries.containsKey("Contents/header.xml")) return style;
        Document header = pack.xml("Contents/header.xml");
        NodeList shapes = header.getElementsByTagNameNS("*", "charPr");
        for (int i = 0; i < shapes.getLength(); i++) { Element cp = (Element) shapes.item(i); if (cp.getAttribute("id").equals(run.getAttribute("charPrIDRef"))) { style.size = number(cp, "height", 1100) / 100; style.bold = ZipXml.child(cp, "bold") != null;
                if (cp.hasAttribute("textColor")) try { style.color = Color.parseColor(cp.getAttribute("textColor")); } catch (IllegalArgumentException ignored) {}
                Element ref = ZipXml.child(cp, "fontRef");
                if (ref != null) { NodeList faces = header.getElementsByTagNameNS("*", "fontface"); for (int f = 0; f < faces.getLength(); f++) { Element face = (Element) faces.item(f); if (face.getAttribute("lang").equals("HANGUL")) for (Element font : ZipXml.children(face, "font")) if (font.getAttribute("id").equals(ref.getAttribute("hangul"))) style.font = font.getAttribute("face"); } }
            } }
        NodeList para = header.getElementsByTagNameNS("*", "paraPr");
        for (int i = 0; i < para.getLength(); i++) { Element ps = (Element) para.item(i); if (ps.getAttribute("id").equals(paragraph.getAttribute("paraPrIDRef"))) { Element align = ZipXml.child(ps, "align"); if (align != null) style.alignment = align.getAttribute("horizontal").toLowerCase(Locale.ROOT); } }
        return style;
    }
    private static float number(Element e, String attribute, float fallback) { if (e == null || !e.hasAttribute(attribute)) return fallback; try { return Float.parseFloat(e.getAttribute(attribute)); } catch (NumberFormatException invalid) { return fallback; } }
    private void validatePaper() throws IOException { if (width < 72 || width > 2000 || height < 72 || height > 3000 || width - left - right < 40 || height - top - bottom < 40) throw new IOException("지원할 수 없는 용지 크기·여백입니다."); }
    private void beginPage() throws IOException { if (++pageCount > 300) throw new IOException("300페이지까지 변환할 수 있습니다."); page = output.startPage(new PdfDocument.PageInfo.Builder(Math.round(width), Math.round(height), pageCount).create()); canvas = page.getCanvas(); canvas.drawColor(Color.WHITE); y = top; }
    private void finishPage() { if (page != null) { output.finishPage(page); page = null; } }
    private void nextPage() throws IOException { finishPage(); beginPage(); }
    private void ensure(float needed) throws IOException { if (page == null) beginPage(); if (y + needed > height - bottom && y > top) nextPage(); }
    private StaticLayout layout(Item item, float available) {
        paint.setColor(Color.BLACK); paint.setTextSize(Math.max(6, Math.min(96, item.style.size))); paint.setTypeface(Typeface.create(item.style.font, item.style.bold ? Typeface.BOLD : Typeface.NORMAL));
        Layout.Alignment alignment = item.style.alignment.equals("center") ? Layout.Alignment.ALIGN_CENTER : item.style.alignment.equals("right") ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        return StaticLayout.Builder.obtain(item.text, 0, item.text.length(), new TextPaint(paint), Math.max(1, (int) available)).setAlignment(alignment).setIncludePad(false).setLineSpacing(2, 1.1f).build();
    }
    private void draw(Item item) throws IOException {
        if (item.pageBreak && y > top) nextPage();
        if (!item.rows.isEmpty()) { table(item); return; }
        if (item.image != null) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(item.image, 0, item.image.length, options);
            if (options.outWidth <= 0) { notices.add("지원하지 않는 이미지 형식이 있습니다."); return; }
            options.inSampleSize = Math.max(1, (int) Math.ceil(Math.max(options.outWidth, options.outHeight) / 1600f)); options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(item.image, 0, item.image.length, options); if (bitmap == null) return;
            float w = Math.min(width - left - right, item.width > 0 ? item.width : bitmap.getWidth()), h = item.height > 0 && item.width > 0 ? item.height * w / item.width : w * bitmap.getHeight() / bitmap.getWidth();
            if (h > height - top - bottom) { w *= (height - top - bottom) / h; h = height - top - bottom; }
            ensure(h + 4); canvas.drawBitmap(bitmap, null, new RectF(left, y, left + w, y + h), null); y += h + 4; bitmap.recycle(); return;
        }
        StaticLayout text = layout(item, width - left - right); ensure(item.before + text.getLineBottom(0)); y += item.before;
        for (int i = 0; i < text.getLineCount(); i++) {
            float lineHeight = text.getLineBottom(i) - text.getLineTop(i); ensure(lineHeight);
            canvas.save(); canvas.clipRect(left, y, width - right, y + lineHeight); canvas.translate(left, y - text.getLineTop(i)); text.draw(canvas); canvas.restore(); y += lineHeight;
        }
        y += item.after;
    }
    private void table(Item table) throws IOException {
        int columns = 1; for (List<Item> row : table.rows) for (Item cell : row) columns = Math.max(columns, cell.col + cell.span);
        if (columns > 256 || table.rows.size() > 10000) throw new IOException("표 크기가 너무 큽니다.");
        float[] colWidth = new float[columns]; float total = 0;
        for (List<Item> row : table.rows) for (Item cell : row) for (int i = cell.col; i < cell.col + cell.span; i++) colWidth[i] = Math.max(colWidth[i], cell.width / cell.span);
        for (int i = 0; i < columns; i++) { if (colWidth[i] <= 0) colWidth[i] = 60; total += colWidth[i]; }
        float scale = Math.min(1, (width - left - right) / total); float[] xs = new float[columns + 1]; xs[0] = left;
        for (int i = 0; i < columns; i++) xs[i + 1] = xs[i] + colWidth[i] * scale;
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG); border.setColor(Color.DKGRAY); border.setStrokeWidth(.5f); border.setStyle(Paint.Style.STROKE);
        for (List<Item> row : table.rows) {
            List<StaticLayout> layouts = new ArrayList<>(); float rowHeight = 18;
            for (Item cell : row) { StaticLayout text = layout(cell, xs[cell.col + cell.span] - xs[cell.col] - 8); layouts.add(text); rowHeight = Math.max(rowHeight, Math.max(cell.height / cell.rowSpan, text.getHeight() + 8)); if (cell.rowSpan > 1) notices.add("행 병합 셀은 행별로 펼쳐 표시됩니다."); }
            float consumed = 0;
            while (consumed < rowHeight) {
                ensure(Math.min(rowHeight - consumed, 18)); float part = Math.min(rowHeight - consumed, height - bottom - y);
                for (int i = 0; i < row.size(); i++) {
                    Item cell = row.get(i); float x = xs[cell.col], end = xs[cell.col + cell.span];
                    canvas.drawRect(x, y, end, y + part, border); canvas.save(); canvas.clipRect(x + 3, y, end - 3, y + part); canvas.translate(x + 4, y + 4 - consumed); layouts.get(i).draw(canvas); canvas.restore();
                }
                y += part; consumed += part; if (consumed < rowHeight) nextPage();
            }
        }
        y += 6;
    }
}
