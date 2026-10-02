package com.homesweetlove.hwpapp;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.homesweetlove.hwpapp.core.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import java.io.*;
import java.util.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.Test;

public class AdvancedPdfTest {
  @Test
  public void pageManagementAndTransparentSignatureSurviveExport() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PDFBoxResourceLoader.init(c);
    File input = File.createTempFile("pages-", ".pdf", c.getCacheDir()),
        other = File.createTempFile("merge-", ".pdf", c.getCacheDir()),
        output = File.createTempFile("output-", ".pdf", c.getCacheDir());
    try (PDDocument d = new PDDocument()) {
      d.addPage(new PDPage(new PDRectangle(200, 300)));
      d.addPage(new PDPage(new PDRectangle(300, 400)));
      d.save(input);
    }
    try (PDDocument d = new PDDocument()) {
      d.addPage(new PDPage(new PDRectangle(400, 500)));
      d.save(other);
    }
    try (PdfDocument pdf = new PdfDocument(c, input)) {
      pdf.rotate(0);
      pdf.reorder(Arrays.asList(1, 0));
      assertEquals(90, pdf.document.getPage(1).getRotation());
      pdf.merge(other);
      assertEquals(3, pdf.count());
      pdf.delete(1);
      assertEquals(2, pdf.count());
      pdf.extract(output, Collections.singletonList(1));
      try (PdfDocument extracted = new PdfDocument(c, output)) {
        assertEquals(1, extracted.count());
        assertEquals(400, extracted.document.getPage(0).getMediaBox().getWidth(), 0);
      }
      Bitmap signature = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888);
      new Canvas(signature).drawColor(Color.BLUE);
      Map<Integer, List<PageCanvas.Mark>> marks = new HashMap<>();
      marks.put(0, Collections.singletonList(new PageCanvas.Mark(signature, 20, 30, 100, 50)));
      pdf.save(output, marks);
      signature.recycle();
      try (PdfDocument saved = new PdfDocument(c, output)) {
        assertEquals(2, saved.count());
        Bitmap rendered = saved.render(0);
        int color = rendered.getPixel(60, 55);
        assertTrue(Color.blue(color) > Color.red(color));
        rendered.recycle();
      }
    }
    input.delete();
    other.delete();
    output.delete();
  }

  @Test
  public void hangulNativeLayoutProducesReadablePdf() throws Exception {
    Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File hwp = File.createTempFile("hangul-", ".hwp", c.getCacheDir()),
        pdf = File.createTempFile("layout-", ".pdf", c.getCacheDir());
    HWPFile model = BlankFileMaker.make();
    model
        .getBodyText()
        .getSectionList()
        .get(0)
        .getParagraph(0)
        .getText()
        .addString("한글 문서 PDF 변환 테스트");
    HWPWriter.toFile(model, hwp.getAbsolutePath());
    HangulDocument source = new HangulDocument(hwp, false);
    DocumentStyle style = source.style(source.blocks.get(0));
    style.size = 20;
    style.bold = true;
    source.format(source.blocks.get(0), style);
    new HangulRenderer(source).export(pdf);
    assertTrue(pdf.length() > 500);
    try (PdfDocument rendered = new PdfDocument(c, pdf)) {
      assertTrue(rendered.count() > 0);
      Bitmap image = rendered.render(0);
      assertTrue(image.getWidth() > 200);
      int dark = 0;
      for (int y = 0; y < image.getHeight(); y += 4)
        for (int x = 0; x < image.getWidth(); x += 4)
          if (Color.red(image.getPixel(x, y)) < 100) dark++;
      assertTrue("text should be visible", dark > 20);
      image.recycle();
    }
    hwp.delete();
    pdf.delete();
  }
}
