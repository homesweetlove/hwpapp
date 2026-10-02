package com.homesweetlove.hwpapp.core;

import static org.junit.Assert.*;

import java.io.*;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.Test;

public class MixedStyleTest {
  @Test
  public void shortTextChangesKeepUnchangedMixedStyles() throws Exception {
    File in = File.createTempFile("mixed-", ".hwp"),
        out = File.createTempFile("mixed-out-", ".hwp");
    HWPFile model = BlankFileMaker.make();
    Paragraph p = model.getBodyText().getSectionList().get(0).getParagraph(0);
    p.getText().addString("앞부분 가운데 뒷부분");
    int id = model.getDocInfo().getCharShapeList().size();
    kr.dogfoot.hwplib.object.docinfo.CharShape emphasis =
        model.getDocInfo().getCharShapeList().get(0).clone();
    emphasis.getProperty().setItalic(true);
    model.getDocInfo().getCharShapeList().add(emphasis);
    p.getCharShape().addParaCharShape(24, id);
    p.getHeader().setCharShapeCount(p.getCharShape().getPositonShapeIdPairList().size());
    HWPWriter.toFile(model, in.getAbsolutePath());
    HangulDocument doc = new HangulDocument(in, false);
    doc.blocks.get(0).set("앞부분 새 내용 뒷부분");
    doc.save(out);
    HangulDocument read = new HangulDocument(out, false);
    assertEquals("앞부분 새 내용 뒷부분", read.blocks.get(0).text);
    Paragraph loaded = read.binaryModel().getBodyText().getSectionList().get(0).getParagraph(0);
    boolean found = false;
    for (kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair pair :
        loaded.getCharShape().getPositonShapeIdPairList())
      if (pair.getPosition() == 25) {
        assertTrue(
            read.binaryModel()
                .getDocInfo()
                .getCharShapeList()
                .get((int) pair.getShapeId())
                .getProperty()
                .isItalic());
        found = true;
      }
    assertTrue(found);
    in.delete();
    out.delete();
  }

  @Test
  public void applyingFontPreservesOtherMixedProperties() throws Exception {
    File in = File.createTempFile("mixed-font-", ".hwp"),
        out = File.createTempFile("mixed-font-out-", ".hwp");
    HWPFile model = BlankFileMaker.make();
    Paragraph p = model.getBodyText().getSectionList().get(0).getParagraph(0);
    p.getText().addString("혼합 서식");
    int id = model.getDocInfo().getCharShapeList().size();
    kr.dogfoot.hwplib.object.docinfo.CharShape shape =
        model.getDocInfo().getCharShapeList().get(0).clone();
    shape.getProperty().setItalic(true);
    model.getDocInfo().getCharShapeList().add(shape);
    p.getCharShape().addParaCharShape(19, id);
    p.getHeader().setCharShapeCount(2);
    HWPWriter.toFile(model, in.getAbsolutePath());
    HangulDocument doc = new HangulDocument(in, false);
    DocumentStyle style = doc.style(doc.blocks.get(0));
    style.bold = true;
    style.size = 20;
    doc.format(doc.blocks.get(0), style);
    doc.save(out);
    HangulDocument read = new HangulDocument(out, false);
    Paragraph result = read.binaryModel().getBodyText().getSectionList().get(0).getParagraph(0);
    assertEquals(2, result.getCharShape().getPositonShapeIdPairList().size());
    long last = result.getCharShape().getPositonShapeIdPairList().get(1).getShapeId();
    assertTrue(
        read.binaryModel()
            .getDocInfo()
            .getCharShapeList()
            .get((int) last)
            .getProperty()
            .isItalic());
    assertTrue(
        read.binaryModel().getDocInfo().getCharShapeList().get((int) last).getProperty().isBold());
    in.delete();
    out.delete();
  }
}
