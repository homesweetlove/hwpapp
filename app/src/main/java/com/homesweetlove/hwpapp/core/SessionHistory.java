package com.homesweetlove.hwpapp.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable single-document checkpoints. Manifest replacement happens after complete snapshot writes. */
public final class SessionHistory {
    public interface Exporter { void write(File file) throws Exception; }
    private final File directory;
    private final Properties manifest = new Properties();
    private final List<String> versions = new ArrayList<>();
    private int cursor = -1;
    private static final long LIMIT = 192L * 1024 * 1024;
    public SessionHistory(File directory) throws IOException {
        this.directory = directory;
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("임시저장 폴더를 만들 수 없습니다.");
        File meta = new File(directory, "session.properties");
        if (meta.exists()) {
            try (InputStream in = new FileInputStream(meta)) { manifest.load(in); }
            try {
                int count = Integer.parseInt(manifest.getProperty("count", "0"));
                if (count < 1 || count > 30) throw new IOException("복구 기록이 올바르지 않습니다.");
                for (int i = 0; i < count; i++) {
                    String file = manifest.getProperty("v" + i, "");
                    if (!file.matches("[a-f0-9-]+\\.(hwp|hwpx|pdf|xls|xlsx)") || !new File(directory, file).isFile()) throw new IOException("복구 파일이 없습니다.");
                    versions.add(file);
                }
                cursor = Integer.parseInt(manifest.getProperty("cursor", "0"));
                if (cursor < 0 || cursor >= count) throw new IOException("복구 위치가 올바르지 않습니다.");
            } catch (NumberFormatException invalid) { throw new IOException("복구 기록이 올바르지 않습니다.", invalid); }
        }
    }
    public boolean available() { return cursor >= 0; }
    public String name() { return manifest.getProperty("name", "문서"); }
    public String extension() { return manifest.getProperty("extension", ""); }
    public File current() { return new File(directory, versions.get(cursor)); }
    public boolean canUndo() { return cursor > 0; }
    public boolean canRedo() { return cursor + 1 < versions.size(); }
    public File undo() throws IOException { if (canUndo()) { cursor--; persist(); } return current(); }
    public File redo() throws IOException { if (canRedo()) { cursor++; persist(); } return current(); }
    public void start(String name, String extension, Exporter exporter) throws Exception {
        // Keep the existing recovery until the new document snapshot is complete.
        File file = snapshot(extension, exporter);
        List<String> old = new ArrayList<>(versions);
        Properties oldManifest = new Properties(); oldManifest.putAll(manifest); int oldCursor = cursor;
        versions.clear(); versions.add(file.getName()); cursor = 0;
        manifest.clear(); manifest.setProperty("name", name); manifest.setProperty("extension", extension);
        try { persist(); } catch (IOException failure) { versions.clear(); versions.addAll(old); cursor = oldCursor; manifest.clear(); manifest.putAll(oldManifest); file.delete(); throw failure; }
        for (String previous : old) new File(directory, previous).delete();
    }
    public void commit(Exporter exporter) throws Exception {
        File next = snapshot(extension(), exporter);
        List<String> old = new ArrayList<>(versions); int oldCursor = cursor; Properties oldManifest = new Properties(); oldManifest.putAll(manifest);
        List<String> delete = new ArrayList<>();
        while (versions.size() > cursor + 1) delete.add(versions.remove(versions.size() - 1));
        versions.add(next.getName()); cursor++;
        long size = 0; for (String path : versions) size += new File(directory, path).length();
        while (versions.size() > 20 || (size > LIMIT && versions.size() > 1)) {
            String path = versions.remove(0); size -= new File(directory, path).length(); delete.add(path); cursor--;
        }
        try { persist(); } catch (IOException failure) { versions.clear(); versions.addAll(old); cursor = oldCursor; manifest.clear(); manifest.putAll(oldManifest); next.delete(); throw failure; }
        for (String path : delete) new File(directory, path).delete();
    }
    private File snapshot(String extension, Exporter exporter) throws Exception {
        if (!Arrays.asList("hwp", "hwpx", "pdf", "xls", "xlsx").contains(extension)) throw new IOException("잘못된 문서 형식입니다.");
        File file = new File(directory, UUID.randomUUID() + "." + extension);
        try { exporter.write(file); if (file.length() == 0 || file.length() > 96L * 1024 * 1024) throw new IOException("임시저장 파일 크기를 확인해 주세요."); }
        catch (Exception failure) { file.delete(); throw failure; }
        return file;
    }
    private void persist() throws IOException {
        manifest.setProperty("count", "" + versions.size()); manifest.setProperty("cursor", "" + cursor);
        for (int i = 0; i < versions.size(); i++) manifest.setProperty("v" + i, versions.get(i));
        File temporary = new File(directory, "session.tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) { manifest.store(out, "HWP Desk recovery"); out.getFD().sync(); }
        Files.move(temporary.toPath(), new File(directory, "session.properties").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public void clear() {
        File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete();
        manifest.clear(); versions.clear(); cursor = -1;
    }
}
