package com.homesweetlove.hwpapp.core;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

/** Bounded ZIP/XML package editing; untouched entries retain their uncompressed bytes. */
public final class ZipXml {
    public static final long MAX_BYTES = 128L * 1024 * 1024;
    public final Map<String, byte[]> entries = new LinkedHashMap<>();
    private final Map<String, Document> modified = new HashMap<>();
    private final Map<String, Document> parsed = new HashMap<>();
    public ZipXml(File file) throws Exception {
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= 10000) throw new IOException("파일 구성 항목이 너무 많습니다.");
                if (entry.isDirectory()) continue;
                if (entries.containsKey(entry.getName())) throw new IOException("중복 ZIP 항목입니다.");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new IOException("압축 해제 크기가 128MB를 넘습니다.");
                    out.write(buffer, 0, count);
                }
                entries.put(entry.getName(), out.toByteArray());
            }
        }
    }
    public Document xml(String name) throws Exception {
        if (modified.containsKey(name)) return modified.get(name);
        if (parsed.containsKey(name)) return parsed.get(name);
        byte[] bytes = entries.get(name);
        if (bytes == null) throw new IOException("필수 항목이 없습니다: " + name);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        for (String feature : Arrays.asList("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
            try { factory.setFeature(feature, false); }
            catch (ParserConfigurationException unsupportedOnAndroid) { /* DTD rejection and entity resolver below remain mandatory. */ }
        }
        // Android's XML implementation does not implement disallow-doctype-decl.
        String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (xml.replace("\u0000", "").toUpperCase(Locale.ROOT).contains("<!DOCTYPE")) throw new IOException("DTD 문서는 지원하지 않습니다.");
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("외부 엔티티는 허용하지 않습니다."); });
        Document document = builder.parse(new ByteArrayInputStream(bytes));
        parsed.put(name, document); return document;
    }
    public void change(String name, Document document) { modified.put(name, document); }
    public void remove(String name) { entries.remove(name); modified.remove(name); parsed.remove(name); }
    public void save(File file) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                byte[] bytes = item.getValue();
                if (modified.containsKey(item.getKey())) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    Transformer transformer = TransformerFactory.newInstance().newTransformer();
                    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
                    transformer.transform(new DOMSource(modified.get(item.getKey())), new StreamResult(buffer));
                    bytes = buffer.toByteArray();
                }
                ZipEntry entry = new ZipEntry(item.getKey());
                if (item.getKey().equals("mimetype")) {
                    CRC32 crc = new CRC32(); crc.update(bytes);
                    entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCrc(crc.getValue());
                }
                out.putNextEntry(entry); out.write(bytes); out.closeEntry();
            }
        }
    }
    public static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        if (parent == null) return result;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && name.equals(node.getLocalName())) result.add((Element) node);
        return result;
    }
    public static Element child(Element parent, String name) {
        List<Element> list = children(parent, name); return list.isEmpty() ? null : list.get(0);
    }
    public static String text(Element parent, String name) {
        Element element = child(parent, name); return element == null ? "" : element.getTextContent();
    }
}
