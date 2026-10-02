package com.homesweetlove.hwpapp;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import java.io.File;
import java.util.*;
import org.junit.Test;

public class PdfSaveTest {
  @Test
  public void overlaysPreservePagesAndRotationAndRemainAligned() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PDFBoxResourceLoader.init(context);
    for (int rotation : new int[] {0, 90, 180, 270}) {
      File original = File.createTempFile("pdf-original-", ".pdf", context.getCacheDir());
      File saved = File.createTempFile("pdf-saved-", ".pdf", context.getCacheDir());
      try (PDDocument document = new PDDocument()) {
        PDPage page = new PDPage(new PDRectangle(400, 600));
        page.setCropBox(new PDRectangle(20, 30, 300, 500));
        page.setRotation(rotation);
        document.addPage(page);
        document.save(original);
      }
      try (PdfDocument pdf = new PdfDocument(context, original)) {
        Bitmap bitmap = pdf.render(0);
        Path stroke = new Path();
        stroke.moveTo(bitmap.getWidth() * .2f, bitmap.getHeight() * .3f);
        stroke.lineTo(bitmap.getWidth() * .4f, bitmap.getHeight() * .3f);
        Map<Integer, List<PageCanvas.Mark>> marks = new HashMap<>();
        marks.put(
            0,
            Arrays.asList(
                new PageCanvas.Mark(stroke),
                new PageCanvas.Mark("한글", bitmap.getWidth() * .2f, bitmap.getHeight() * .5f)));
        pdf.save(saved, marks);
        pdf.save(saved, marks);
        bitmap.recycle();
      }
      try (PdfDocument output = new PdfDocument(context, saved)) {
        assertEquals(1, output.count());
        assertEquals(rotation, output.document.getPage(0).getRotation());
        Bitmap bitmap = output.render(0);
        boolean blue = false;
        int centerX = (int) (bitmap.getWidth() * .3f), centerY = (int) (bitmap.getHeight() * .3f);
        for (int y = centerY - 5; y <= centerY + 5; y++) {
          int color = bitmap.getPixel(centerX, y);
          if (Color.blue(color) > Color.red(color) + 50) blue = true;
        }
        assertTrue("Ink alignment for page rotation " + rotation, blue);
        bitmap.recycle();
      }
      original.delete();
      saved.delete();
    }
  }
}
