package com.homesweetlove.hwpapp;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

final class PdfDocument implements AutoCloseable {
    final PDDocument document;
    PdfDocument(Context context, File file) throws Exception {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        document = PDDocument.load(file);
        if (document.isEncrypted() || !document.getCurrentAccessPermission().canModify()) {
            document.close(); throw new IOException("암호화되었거나 수정이 제한된 PDF입니다.");
        }
        if (document.getNumberOfPages() == 0) { document.close(); throw new IOException("빈 PDF입니다."); }
    }
    int count() { return document.getNumberOfPages(); }
    Bitmap render(int index) throws IOException {
        PDPage page = document.getPage(index);
        PDRectangle box = page.getCropBox();
        float side = Math.max(box.getWidth(), box.getHeight());
        if (side <= 0 || !Float.isFinite(side)) throw new IOException("잘못된 PDF 페이지 크기입니다.");
        return new PDFRenderer(document).renderImage(index, Math.min(2f, 1400f / side));
    }
    void save(File file, java.util.Map<Integer, java.util.List<PageCanvas.Mark>> overlays) throws Exception {
        // Edit a temporary copy: repeated exports must not apply the same overlay twice.
        File source = File.createTempFile("pdf-source-", ".pdf", file.getParentFile());
        document.save(source);
        try (PDDocument output = PDDocument.load(source)) {
            for (java.util.Map.Entry<Integer, java.util.List<PageCanvas.Mark>> entry : overlays.entrySet()) {
                if (entry.getValue().isEmpty()) continue;
                PDPage page = output.getPage(entry.getKey());
                Bitmap background = render(entry.getKey());
                Bitmap image = PageCanvas.renderLayer(background, entry.getValue());
                background.recycle();
                int rotation = ((page.getRotation() % 360) + 360) % 360;
                Bitmap upright = image;
                if (rotation != 0) {
                    Matrix matrix = new Matrix(); matrix.postRotate(-rotation);
                    upright = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
                }
                PDRectangle crop = page.getCropBox();
                try (PDPageContentStream stream = new PDPageContentStream(output, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.drawImage(LosslessFactory.createFromImage(output, upright), crop.getLowerLeftX(), crop.getLowerLeftY(), crop.getWidth(), crop.getHeight());
                }
                if (upright != image) upright.recycle();
                image.recycle();
            }
            output.save(file);
        } finally { source.delete(); }
    }
    public void close() { try { document.close(); } catch (IOException ignored) { } }
}
