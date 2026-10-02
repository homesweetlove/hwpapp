package com.homesweetlove.hwpapp;

import android.os.*;
import android.print.*;
import com.tom_roush.pdfbox.pdmodel.*;
import java.io.*;
import java.util.concurrent.*;

/** Owns an exported PDF independently of the editor Activity's lifetime. */
final class PdfPrintAdapter extends PrintDocumentAdapter {
  private final File file;
  private final String name;
  private final int count;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();

  PdfPrintAdapter(File file, String name, int count) {
    this.file = file;
    this.name = name;
    this.count = count;
  }

  @Override
  public void onLayout(
      PrintAttributes old,
      PrintAttributes current,
      CancellationSignal cancellation,
      LayoutResultCallback callback,
      Bundle extras) {
    if (cancellation.isCanceled()) {
      callback.onLayoutCancelled();
      return;
    }
    callback.onLayoutFinished(
        new PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(count)
            .build(),
        true);
  }

  @Override
  public void onWrite(
      PageRange[] ranges,
      ParcelFileDescriptor destination,
      CancellationSignal cancellation,
      WriteResultCallback callback) {
    worker.execute(
        () -> {
          try (PDDocument source = PDDocument.load(file);
              PDDocument selected = new PDDocument();
              OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
            for (int i = 0; i < count; i++) {
              if (cancellation.isCanceled()) {
                callback.onWriteCancelled();
                return;
              }
              boolean include = false;
              for (PageRange range : ranges)
                if (range.getStart() <= i && range.getEnd() >= i) {
                  include = true;
                  break;
                }
              if (include) selected.importPage(source.getPage(i));
            }
            if (selected.getNumberOfPages() == 0) {
              callback.onWriteFailed("인쇄할 페이지가 없습니다.");
              return;
            }
            selected.save(out);
            if (cancellation.isCanceled()) callback.onWriteCancelled();
            else callback.onWriteFinished(ranges);
          } catch (Exception e) {
            callback.onWriteFailed(e.getMessage());
          }
        });
  }

  @Override
  public void onFinish() {
    worker.execute(() -> file.delete());
    worker.shutdown();
  }
}
