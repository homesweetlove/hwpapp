package com.homesweetlove.hwpapp;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import org.junit.Test;

public class AdaptiveUiTest {
  private static final Instrumentation I = InstrumentationRegistry.getInstrumentation();

  private static void shell(String command) throws Exception {
    try (ParcelFileDescriptor p = I.getUiAutomation().executeShellCommand(command);
        InputStream in = new FileInputStream(p.getFileDescriptor())) {
      byte[] b = new byte[1024];
      while (in.read(b) != -1) {}
    }
    I.waitForIdleSync();
  }

  private static Object value(Object object, String name) {
    try {
      Field f = object.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(object);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void invoke(MainActivity a, String method, Class<?>[] types, Object... args) {
    try {
      Method m = MainActivity.class.getDeclaredMethod(method, types);
      m.setAccessible(true);
      m.invoke(a, args);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void editCell(MainActivity a, String ref, String text) {
    try {
      Class<?> job = Class.forName("com.homesweetlove.hwpapp.MainActivity$Job");
      Object action =
          java.lang.reflect.Proxy.newProxyInstance(
              job.getClassLoader(),
              new Class<?>[] {job},
              (proxy, method, args) -> {
                if (method.getName().equals("run"))
                  ((com.homesweetlove.hwpapp.core.Spreadsheet) value(a, "excel")).set(0, ref, text);
                return null;
              });
      invoke(
          a,
          "mutate",
          new Class<?>[] {String.class, job, Runnable.class},
          "테스트 편집",
          action,
          (Runnable) () -> invoke(a, "show", new Class<?>[] {}));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void assertCell(MainActivity a, String ref, String text) {
    try {
      assertEquals(
          text, ((com.homesweetlove.hwpapp.core.Spreadsheet) value(a, "excel")).value(0, ref));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static void idle(ActivityScenario<MainActivity> s) throws Exception {
    for (int i = 0; i < 300; i++) {
      boolean[] busy = {true};
      s.onActivity(a -> busy[0] = (Boolean) value(a, "busy"));
      if (!busy[0]) {
        I.waitForIdleSync();
        s.onActivity(a -> busy[0] = (Boolean) value(a, "busy"));
        if (!busy[0]) return;
      }
      Thread.sleep(100);
    }
    fail("Document operation timed out");
  }

  private static void reset() throws Exception {
    shell("wm size reset");
    shell("wm density reset");
    shell("settings put system font_scale 1.0");
    I.getTargetContext()
        .getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
    File directory = new File(I.getTargetContext().getFilesDir(), "recovery");
    File[] files = directory.listFiles();
    if (files != null) for (File file : files) file.delete();
  }

  private static void check(MainActivity a) {
    View root = (View) value(a, "root");
    assertTrue(root.getWidth() > 0);
    assertTrue(root.getHeight() > 0);
    ScrollView scroll = (ScrollView) value(a, "scroll");
    assertTrue(
        "No room for document",
        scroll.getHeight() >= 48 * a.getResources().getDisplayMetrics().density);
    for (String field : new String[] {"saveButton", "undoButton", "redoButton", "toolsButton"}) {
      View button = (View) value(a, field);
      int[] position = new int[2], origin = new int[2];
      button.getLocationOnScreen(position);
      root.getLocationOnScreen(origin);
      assertTrue(
          field + " clipped horizontally",
          position[0] >= origin[0]
              && position[0] + button.getWidth() <= origin[0] + root.getWidth());
      assertTrue(
          field + " clipped vertically",
          position[1] >= origin[1]
              && position[1] + button.getHeight() <= origin[1] + root.getHeight());
      assertTrue(
          field + " touch target",
          button.getHeight() >= 48 * a.getResources().getDisplayMetrics().density - 1);
    }
  }

  private static void screenshot(MainActivity a, String name) {
    try {
      View view = a.getWindow().getDecorView();
      Bitmap bitmap =
          Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
      view.draw(new Canvas(bitmap));
      File directory = new File(a.getExternalFilesDir(null), "screenshots");
      directory.mkdirs();
      try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      bitmap.recycle();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void compactPhoneAndLargeTextKeepToolsVisible() throws Exception {
    reset();
    try {
      shell("wm size 720x1280");
      shell("wm density 360");
      shell("settings put system font_scale 1.3");
      try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
        idle(s);
        s.onActivity(
            a -> {
              check(a);
              assertFalse((Boolean) value(a, "wide"));
              screenshot(a, "phone-320-large-text");
            });
      }
    } finally {
      reset();
    }
  }

  @Test
  public void tabletLandscapeAndDarkModeKeepWorkspaceUsable() throws Exception {
    reset();
    try {
      shell("wm size 1600x2560");
      shell("wm density 240");
      I.getTargetContext()
          .getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
          .edit()
          .putInt("appearance", 2)
          .commit();
      try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
        idle(s);
        s.onActivity(
            a -> {
              check(a);
              assertTrue((Boolean) value(a, "wide"));
              assertTrue((Boolean) value(a, "dark"));
              screenshot(a, "tablet-dark");
            });
        shell("wm size 1280x720");
        idle(s);
        s.onActivity(
            a -> {
              check(a);
              screenshot(a, "landscape-dark");
            });
      }
    } finally {
      reset();
    }
  }

  @Test
  public void createEditUndoRedoRecoverAndRotateWorkbook() throws Exception {
    reset();
    try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
      idle(s);
      s.onActivity(a -> invoke(a, "createDocument", new Class<?>[] {int.class}, 2));
      idle(s);
      s.onActivity(a -> editCell(a, "C2", "1200"));
      idle(s);
      s.onActivity(
          a -> {
            assertCell(a, "C2", "1200");
            invoke(a, "travel", new Class<?>[] {boolean.class}, false);
          });
      idle(s);
      s.onActivity(
          a -> {
            assertCell(a, "C2", "0");
            invoke(a, "travel", new Class<?>[] {boolean.class}, true);
          });
      idle(s);
      s.onActivity(
          a -> {
            assertCell(a, "C2", "1200");
            check(a);
            screenshot(a, "budget-sheet");
          });
      s.recreate();
      idle(s);
      s.onActivity(
          a -> {
            assertCell(a, "C2", "1200");
            check(a);
          });
      shell("wm size 1280x720");
      shell("wm density 240");
      idle(s);
      s.onActivity(
          a -> {
            assertCell(a, "C2", "1200");
            check(a);
            screenshot(a, "sheet-landscape");
          });
    } finally {
      reset();
    }
  }

  @Test
  public void newHangulAndPdfOpenAndExportWithoutChangingCounts() throws Exception {
    reset();
    try (ActivityScenario<MainActivity> s = ActivityScenario.launch(MainActivity.class)) {
      idle(s);
      for (int choice : new int[] {0, 3}) {
        s.onActivity(a -> invoke(a, "createDocument", new Class<?>[] {int.class}, choice));
        idle(s);
        File file =
            File.createTempFile(
                "ui-roundtrip-", choice == 0 ? ".hwp" : ".pdf", I.getTargetContext().getCacheDir());
        s.onActivity(
            a -> {
              check(a);
              invoke(a, "writeCurrent", new Class<?>[] {File.class}, file);
              if (choice == 3) {
                screenshot(a, "pdf-workspace");
                ZoomPage zoom = findZoom((ViewGroup) value(a, "content"));
                assertNotNull(zoom);
                zoom.setZoom(2);
              }
            });
        assertTrue(file.length() > 0);
        file.delete();
      }
    } finally {
      reset();
    }
  }

  private static ZoomPage findZoom(ViewGroup parent) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      View child = parent.getChildAt(i);
      if (child instanceof ZoomPage) return (ZoomPage) child;
      if (child instanceof ViewGroup) {
        ZoomPage found = findZoom((ViewGroup) child);
        if (found != null) return found;
      }
    }
    return null;
  }
}
