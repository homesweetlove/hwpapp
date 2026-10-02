package com.homesweetlove.hwpapp;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.homesweetlove.hwpapp.core.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final int OPEN = 1,
      SAVE = 2,
      MERGE = 3,
      SIGNATURE = 4,
      EXPORT_PDF = 5,
      EXTRACT = 6,
      ORIGINAL = 7,
      CSV = 8;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private LinearLayout root, content;
  private TextView subtitle, status;
  private ScrollView scroll;
  private SessionHistory history;
  private HangulDocument hangul;
  private Spreadsheet excel;
  private PdfDocument pdf, layoutPdf;
  private File input, layoutFile;
  private String name = "", extension = "", layoutNotice = "";
  private Bitmap pageBitmap;
  private PageCanvas pageCanvas;
  private final Map<Integer, List<PageCanvas.Mark>> marks = new HashMap<>();
  private int page, layoutPage, blockStart, sheet, firstRow, firstCol;
  private boolean busy, destroyed, dirty, layoutMode, inkMode;
  private List<Integer> extraction;
  private int ink, green, background, surface, muted, border;
  private boolean dark, wide;
  private Button undoButton, redoButton, saveButton, toolsButton;
  private ProgressBar progress;
  private float zoom = 1f;
  private int penColor = 0xff3182f6;
  private float penWidth = 3f;
  private boolean rebuildPending;

  private interface Job {
    void run() throws Exception;
  }

  private interface TextAction {
    void accept(String value);
  }

  @Override
  public void onCreate(Bundle state) {
    applyPalette();
    setTheme(dark ? R.style.AppThemeDark : R.style.AppTheme);
    super.onCreate(state);
    buildShell();
    home();
    job(
        "복구 기록 확인 중…",
        () -> history = new SessionHistory(new File(getFilesDir(), "recovery")),
        () -> {
          if (state != null && state.getBoolean("active") && history.available())
            job(
                "작업 이어 여는 중…",
                () -> {
                  name = history.name();
                  extension = history.extension();
                  load(history.current());
                  page = state.getInt("page");
                  sheet =
                      Math.min(state.getInt("sheet"), excel == null ? 0 : excel.names.size() - 1);
                  firstRow = state.getInt("row");
                  firstCol = state.getInt("col");
                  blockStart = state.getInt("block");
                  zoom = state.getFloat("zoom", 1);
                  dirty = state.getBoolean("dirty");
                },
                this::show);
          else if (getIntent().getData() != null) open(getIntent().getData());
          else if (history.available())
            new AlertDialog.Builder(this)
                .setTitle("자동저장 문서 복구")
                .setMessage(history.name() + "의 마지막 작업을 이어서 열까요?")
                .setNegativeButton("나중에", (d, w) -> home())
                .setPositiveButton("복구", (d, w) -> restore())
                .show();
        });
  }

  private void applyPalette() {
    int mode = getPreferences(MODE_PRIVATE).getInt("appearance", 0);
    dark =
        mode == 2
            || mode == 0
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
    ink = Color.parseColor(dark ? "#F2F4F6" : "#191F28");
    green = Color.parseColor(dark ? "#6CA8FF" : "#3182F6");
    background = Color.parseColor(dark ? "#101318" : "#F2F4F6");
    surface = Color.parseColor(dark ? "#1C222B" : "#FFFFFF");
    muted = Color.parseColor(dark ? "#A5B0BD" : "#6B7684");
    border = Color.parseColor(dark ? "#303945" : "#E5E8EB");
  }

  private void buildShell() {
    wide = getResources().getConfiguration().screenWidthDp >= 840;
    root = vertical();
    root.setBackgroundColor(background);
    setContentView(root);
    getWindow().setStatusBarColor(background);
    getWindow().setNavigationBarColor(surface);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int left = insets.getSystemWindowInsetLeft(),
              top = insets.getSystemWindowInsetTop(),
              right = insets.getSystemWindowInsetRight(),
              bottom = insets.getSystemWindowInsetBottom();
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets safe =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            left = safe.left;
            top = safe.top;
            right = safe.right;
            bottom = safe.bottom;
          } else if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
            right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
            top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
          }
          root.setPadding(left, top, right, bottom);
          return insets;
        });
    root.requestApplyInsets();
    LinearLayout header = vertical();
    header.setPadding(dp(24), dp(14), dp(24), dp(10));
    TextView title = label("HWP Desk", 22, ink);
    title.setTypeface(null, Typeface.BOLD);
    header.addView(title);
    subtitle = label(input == null ? "내 문서를 위한 작은 작업실" : name, 13, muted);
    subtitle.setMaxLines(1);
    subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    header.addView(subtitle);
    root.addView(header);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    progress.setVisibility(View.GONE);
    root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.HORIZONTAL);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    if (wide) {
      LinearLayout rail = vertical();
      rail.setPadding(dp(16), dp(16), dp(16), dp(16));
      rail.setBackgroundColor(surface);
      body.addView(rail, new LinearLayout.LayoutParams(dp(208), -1));
      rail.addView(label("작업 공간", 12, muted));
      rail.addView(button("문서 열기", () -> guard(() -> picker(OPEN, "*/*"))));
      rail.addView(button("새 문서", this::newDocumentMenu));
      rail.addView(button("문서 도구", this::tools));
      rail.addView(button("모양 설정", this::appearance));
      rail.addView(label("오프라인으로\n차분하게 작업하세요.", 14, muted));
    }
    FrameLayout host = new FrameLayout(this);
    body.addView(host, new LinearLayout.LayoutParams(0, -1, 1));
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setPadding(dp(wide ? 28 : 20), dp(12), dp(wide ? 28 : 20), dp(24));
    FrameLayout.LayoutParams scrollParams =
        new FrameLayout.LayoutParams(
            wide
                ? Math.min(dp(1100), getResources().getDisplayMetrics().widthPixels - dp(220))
                : -1,
            -1,
            Gravity.CENTER_HORIZONTAL);
    host.addView(scroll, scrollParams);
    content = vertical();
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    LinearLayout bottom = vertical();
    bottom.setBackgroundColor(surface);
    bottom.setPadding(dp(16), dp(8), dp(16), dp(8));
    root.addView(bottom);
    LinearLayout actions = horizontal();
    bottom.addView(actions);
    actions.addView(button("열기", () -> guard(() -> picker(OPEN, "*/*"))));
    saveButton = button("저장", () -> exportPicker(SAVE));
    actions.addView(saveButton);
    undoButton = button("↶", () -> travel(false));
    undoButton.setContentDescription("실행 취소");
    actions.addView(undoButton);
    redoButton = button("↷", () -> travel(true));
    redoButton.setContentDescription("다시 실행");
    actions.addView(redoButton);
    toolsButton = button("도구", this::tools);
    actions.addView(toolsButton);
    status = label("HWP · HWPX · PDF · XLSX · XLS", 11, muted);
    status.setMaxLines(2);
    bottom.addView(status);
    updateStatus();
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout horizontal() {
    return new ActionRow(this);
  }

  private TextView label(String text, int size, int color) {
    TextView v = new TextView(this);
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setPadding(0, dp(6), 0, dp(6));
    return v;
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTypeface(null, Typeface.BOLD);
    b.setTextColor(green);
    b.setMinWidth(dp(48));
    b.setMinimumWidth(dp(48));
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    b.setBackground(round(surface, 14));
    b.setStateListAnimator(null);
    b.setOnClickListener(
        v -> {
          if (!busy) action.run();
        });
    return b;
  }

  private GradientDrawable round(int color, int radius) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(color);
    bg.setCornerRadius(dp(radius));
    return bg;
  }

  private LinearLayout card(String tag, String title, String detail, Runnable action) {
    LinearLayout c = vertical();
    c.setPadding(dp(22), dp(18), dp(22), dp(18));
    c.setBackground(round(surface, 22));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(12);
    c.setLayoutParams(p);
    TextView badge = label(tag, 12, green);
    badge.setTypeface(null, Typeface.BOLD);
    c.addView(badge);
    TextView heading = label(title, 20, ink);
    heading.setTypeface(null, Typeface.BOLD);
    c.addView(heading);
    c.addView(label(detail, 14, muted));
    if (action != null) {
      c.setOnClickListener(
          v -> {
            if (!busy) action.run();
          });
      c.setFocusable(true);
      c.setContentDescription(title + ". " + detail);
    }
    return c;
  }

  private void home() {
    content.removeAllViews();
    TextView hero = label("문서 작업을\n가볍게", 34, ink);
    hero.setTypeface(null, Typeface.BOLD);
    hero.setPadding(0, dp(16), 0, dp(14));
    content.addView(hero);
    content.addView(label("한글부터 엑셀까지, 한 곳에서.", 16, muted));
    LinearLayout quick = horizontal();
    quick.setPadding(0, dp(12), 0, dp(20));
    Button open = button("문서 열기", () -> guard(() -> picker(OPEN, "*/*")));
    open.setTextColor(Color.WHITE);
    open.setBackground(round(0xff3182f6, 14));
    quick.addView(open);
    quick.addView(button("새 문서", this::newDocumentMenu));
    quick.addView(button("모양", this::appearance));
    content.addView(quick);
    if (history != null && history.available())
      content.addView(card("이어 하기", history.name(), "자동 저장된 마지막 작업을 이어서 열어요", this::restore));
    content.addView(
        card("HWP · HWPX", "한글 문서", "문단과 서식을 고치고 PDF로 내보내세요", () -> picker(OPEN, "*/*")));
    content.addView(
        card("XLSX · XLS", "엑셀 작업", "함수 계산, 범위 붙여넣기, 셀 서식을 한 번에", () -> picker(OPEN, "*/*")));
    content.addView(
        card(
            "PDF",
            "읽기부터 서명까지",
            "확대해서 읽고, 필기하고, 페이지를 정리하세요",
            () -> picker(OPEN, "application/pdf")));
    content.addView(label("편집은 자동 저장 · 원본은 별도 보관\n문서는 기기 안에서 처리됩니다.", 13, muted));
  }

  private void newDocumentMenu() {
    guard(
        () ->
            menu(
                "새로 만들기",
                new String[] {"빈 한글 문서", "빈 엑셀 시트", "가계부 엑셀", "빈 PDF"},
                this::createDocument));
  }

  private void createDocument(int choice) {
    job(
        "새 문서 만드는 중…",
        () -> {
          String ext = choice == 0 ? "hwp" : choice == 3 ? "pdf" : "xlsx";
          File f = File.createTempFile("new-", "." + ext, getCacheDir());
          try {
            if (choice == 0) NewDocuments.hangul(f);
            else if (choice == 3) PdfDocument.blank(this, f);
            else NewDocuments.spreadsheet(f, choice == 2);
            String title = (choice == 2 ? "가계부" : "새 문서") + "." + ext;
            history.start(title, ext, target -> copy(f, target));
            name = title;
            extension = ext;
            load(history.current());
            resetPosition();
            dirty = true;
          } finally {
            f.delete();
          }
        },
        this::show);
  }

  private void appearance() {
    String[] names = {"기기 설정 따르기", "밝게", "어둡게"};
    new AlertDialog.Builder(this)
        .setTitle("화면 모양")
        .setSingleChoiceItems(
            names,
            getPreferences(MODE_PRIVATE).getInt("appearance", 0),
            (d, i) -> {
              getPreferences(MODE_PRIVATE).edit().putInt("appearance", i).apply();
              d.dismiss();
              recreate();
            })
        .setNegativeButton("닫기", null)
        .show();
  }

  private void capabilities() {
    String text =
        input == null
            ? "한글 · 엑셀 · PDF를 읽고 수정합니다."
            : name
                + "\n"
                + String.format(Locale.KOREA, "%.1f MB", input.length() / 1048576d)
                + "\n"
                + (hangul != null
                    ? hangul.blocks.size() + "개 텍스트 항목"
                    : excel != null ? excel.names.size() + "개 시트" : pdf.count() + "페이지");
    new AlertDialog.Builder(this)
        .setTitle("문서 작업 안내")
        .setMessage(
            text
                + "\n\n"
                + "원본은 처음 연 상태로 별도 보관합니다. 편집은 자동 저장되며 최대 20개 기록을 되돌릴 수 있습니다.\n\n"
                + "한글의 복잡한 배치와 기기에 없는 글꼴은 다르게 보일 수 있습니다. 엑셀은 "
                + SpreadsheetFunctions.names().size()
                + "개 함수를 지원하며 미지원 함수는 오류로 표시합니다. PDF는 본문 위에 글자·필기·서명을 추가합니다. 서명된 문서를 편집하면 전자서명이"
                + " 유효하지 않을 수 있습니다.")
        .setPositiveButton("확인", null)
        .show();
  }

  private void clipboard(String text) {
    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("HWP Desk", text));
    message("클립보드에 복사했습니다.");
  }

  private void rangeTools() {
    menu(
        "범위 작업",
        new String[] {"값 복사", "값·수식 원문 복사", "붙여넣기", "범위 서식", "범위 내용 비우기"},
        i -> {
          if (i == 2) {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
              message("클립보드에 내용이 없습니다.");
              return;
            }
            String text = clip.getItemAt(0).coerceToText(this).toString();
            edit(
                "붙여넣을 시작 셀",
                Spreadsheet.address(firstCol, firstRow),
                false,
                ref ->
                    new AlertDialog.Builder(this)
                        .setTitle("범위 붙여넣기")
                        .setMessage(
                            "탭·줄바꿈으로 구분된 내용을 입력합니다. 수식 참조는 복사한 원문 그대로 붙여넣습니다. 기존 셀 값은 덮어씁니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton(
                            "붙여넣기",
                            (d, w) ->
                                mutate("붙여넣는 중…", () -> excel.paste(sheet, ref, text), this::show))
                        .show());
            return;
          }
          edit(
              "셀 범위 (예: A1:D10)",
              Spreadsheet.address(firstCol, firstRow)
                  + ":"
                  + Spreadsheet.address(
                      firstCol + Math.min(3, (extension.equals("xls") ? 255 : 16383) - firstCol),
                      Math.min(firstRow + 9, extension.equals("xls") ? 65535 : 1048575)),
              false,
              range -> {
                if (i < 2)
                  job(
                      "범위 복사 중…",
                      () -> {
                        String text = excel.copyRange(sheet, range, i == 0);
                        runOnUiThread(
                            () -> {
                              if (!destroyed) clipboard(text);
                            });
                      },
                      () -> {});
                else if (i == 3)
                  styleDialog(
                      new DocumentStyle(),
                      true,
                      format ->
                          mutate(
                              "범위 서식 적용 중…",
                              () -> {
                                for (String ref : Spreadsheet.range(range))
                                  excel.format(sheet, ref, format);
                              },
                              this::show));
                else
                  new AlertDialog.Builder(this)
                      .setTitle("범위 내용 비우기")
                      .setMessage(range + "의 값과 수식을 지웁니다. 서식은 유지합니다.")
                      .setNegativeButton("취소", null)
                      .setPositiveButton(
                          "비우기",
                          (d, w) ->
                              mutate(
                                  "범위 비우는 중…",
                                  () -> {
                                    for (String ref : Spreadsheet.range(range))
                                      excel.set(sheet, ref, "");
                                  },
                                  this::show))
                      .show();
              });
        });
  }

  private void functionHelp() {
    LinearLayout form = vertical();
    form.setPadding(dp(20), dp(4), dp(20), dp(12));
    EditText search = field("함수 이름 검색", "");
    form.addView(search);
    ListView list = new ListView(this);
    List<String> functions = SpreadsheetFunctions.names();
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>(functions));
    list.setAdapter(adapter);
    form.addView(list, new LinearLayout.LayoutParams(-1, dp(280)));
    search.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            adapter.getFilter().filter(s);
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
    list.setOnItemClickListener(
        (parent, view, position, id) -> {
          String f = adapter.getItem(position);
          new AlertDialog.Builder(this)
              .setTitle(f)
              .setMessage(
                  "셀에 ="
                      + f
                      + "(...) 형식으로 입력하세요.\n\n"
                      + "예: =SUM(A1:A10)\n"
                      + "=IF(B2>0,\"완료\",\"대기\")\n"
                      + "=SUMIFS(C2:C10,A2:A10,\"식비\")\n"
                      + "=XLOOKUP(E2,A2:A10,B2:B10)\n\n"
                      + "범위는 한 수식에서 10,000셀까지 지원합니다. XLOOKUP은 정확히 일치·와일드카드 검색을 지원합니다.")
              .setPositiveButton("닫기", null)
              .setNeutralButton("이름 복사", (d, w) -> clipboard(f))
              .show();
        });
    new AlertDialog.Builder(this)
        .setTitle("지원 함수 " + functions.size() + "개")
        .setView(form)
        .setPositiveButton("닫기", null)
        .show();
  }

  private void searchPdf() {
    edit(
        "PDF에서 찾기",
        "",
        false,
        query -> {
          if (query.isEmpty()) return;
          int[] found = {-1};
          job(
              "페이지 검색 중…",
              () -> {
                for (int offset = 1; offset <= Math.min(pdf.count(), 300); offset++) {
                  int i = (page + offset) % pdf.count();
                  if (pdf.text(i)
                      .toLowerCase(Locale.ROOT)
                      .contains(query.toLowerCase(Locale.ROOT))) {
                    found[0] = i;
                    break;
                  }
                }
              },
              () -> {
                if (found[0] < 0) message("찾는 글자가 없습니다. 스캔된 이미지 PDF는 글자 검색을 지원하지 않습니다.");
                else {
                  page = found[0];
                  showPdf();
                }
              });
        });
  }

  private void penSettings() {
    String[] colors = {"파랑", "검정", "빨강", "초록"};
    int[] values = {0xff3182f6, 0xff191f28, 0xffe5484d, 0xff229c6a};
    menu(
        "펜 색",
        colors,
        i -> {
          penColor = values[i];
          edit(
              "펜 두께 (1–20)",
              "" + penWidth,
              false,
              value -> {
                try {
                  float width = Float.parseFloat(value);
                  if (!Float.isFinite(width) || width < 1 || width > 20)
                    throw new IllegalArgumentException("1–20 사이로 입력하세요.");
                  penWidth = width;
                  if (pageCanvas != null) {
                    pageCanvas.strokeColor = penColor;
                    pageCanvas.strokeWidth = penWidth;
                  }
                } catch (Exception e) {
                  error(e);
                }
              });
        });
  }

  private void share() {
    File[] output = {null};
    job(
        "공유 파일 준비 중…",
        () -> {
          File directory = new File(getCacheDir(), "shared");
          if (!directory.exists() && !directory.mkdirs())
            throw new IOException("공유 폴더를 만들 수 없습니다.");
          File[] stale = directory.listFiles();
          if (stale != null)
            for (File f : stale)
              if (System.currentTimeMillis() - f.lastModified() > 86400000) f.delete();
          String clean = name.replaceAll("[^\\p{L}\\p{N} ._-]", "_");
          if (clean.length() > 100) clean = "수정본." + extension;
          output[0] = new File(directory, UUID.randomUUID() + "-" + clean);
          writeCurrent(output[0]);
        },
        () -> {
          Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", output[0]);
          Intent i =
              new Intent(Intent.ACTION_SEND)
                  .setType(mime())
                  .putExtra(Intent.EXTRA_STREAM, uri)
                  .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          i.setClipData(ClipData.newRawUri("수정본", uri));
          startActivity(Intent.createChooser(i, "수정본 공유"));
        });
  }

  private void writePdf(File file) throws Exception {
    if (pdf != null) writeCurrent(file);
    else if (hangul != null) new HangulRenderer(hangul).export(file);
    else SpreadsheetPdf.export(excel, sheet, file);
  }

  private void print() {
    File[] f = {null};
    int[] count = {0};
    job(
        "인쇄 준비 중…",
        () -> {
          f[0] = File.createTempFile("print-", ".pdf", getCacheDir());
          try {
            writePdf(f[0]);
            try (PdfDocument check = new PdfDocument(this, f[0])) {
              count[0] = check.count();
            }
          } catch (Exception e) {
            f[0].delete();
            throw e;
          }
        },
        () -> {
          PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
          manager.print(
              name,
              new PdfPrintAdapter(f[0], name, count[0]),
              new PrintAttributes.Builder().build());
        });
  }

  private void picker(int request, String mime) {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(i, request);
  }

  private void exportPicker(int request) {
    if (input == null) {
      message("먼저 문서를 열어 주세요.");
      return;
    }
    String ext =
        request == SAVE || request == ORIGINAL ? extension : request == CSV ? "csv" : "pdf";
    Intent i =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(
                request == SAVE || request == ORIGINAL
                    ? mime()
                    : request == CSV ? "text/csv" : "application/pdf");
    i.putExtra(
        Intent.EXTRA_TITLE,
        name.replaceFirst("(?i)\\.[a-z0-9]+$", "") + (request == EXTRACT ? "_추출." : "_수정본.") + ext);
    startActivityForResult(i, request);
  }

  private String mime() {
    switch (extension) {
      case "pdf":
        return "application/pdf";
      case "xlsx":
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      case "xls":
        return "application/vnd.ms-excel";
      case "hwpx":
        return "application/vnd.hancom.hwpx";
      default:
        return "application/x-hwp";
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == OPEN) {
      try {
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
          getContentResolver()
              .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException ignored) {
      }
      open(uri);
    } else if (request == SAVE
        || request == EXPORT_PDF
        || request == EXTRACT
        || request == ORIGINAL
        || request == CSV) export(uri, request);
    else if (request == MERGE)
      mutate(
          "PDF 병합 중…",
          () -> {
            flattenPdf();
            File f = importFile(uri, ".pdf");
            try {
              pdf.merge(f);
            } finally {
              f.delete();
            }
          },
          this::show);
    else if (request == SIGNATURE) signature(uri);
  }

  private File importFile(Uri uri, String suffix) throws Exception {
    File local = File.createTempFile("document-", suffix, getCacheDir());
    try (InputStream in = getContentResolver().openInputStream(uri);
        OutputStream out = new FileOutputStream(local)) {
      if (in == null) throw new IOException("파일을 열 수 없습니다.");
      byte[] b = new byte[8192];
      int n;
      long total = 0;
      while ((n = in.read(b)) != -1) {
        total += n;
        if (total > 64L * 1024 * 1024) throw new IOException("64MB 이하의 파일을 선택하세요.");
        out.write(b, 0, n);
      }
    } catch (Exception e) {
      local.delete();
      throw e;
    }
    return local;
  }

  private void open(Uri uri) {
    job(
        "문서 여는 중…",
        () -> {
          String display = "문서";
          try (Cursor cursor =
              getContentResolver()
                  .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) display = cursor.getString(0);
          }
          String ext =
              display != null && display.contains(".")
                  ? display.substring(display.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                  : "";
          if (!Arrays.asList("hwp", "hwpx", "pdf", "xlsx", "xls").contains(ext))
            throw new IOException("HWP·HWPX·PDF·XLSX·XLS 파일을 선택하세요.");
          File local = importFile(uri, "." + ext);
          try {
            // Validate before replacing the previous recovery document.
            if (ext.equals("pdf")) {
              try (PdfDocument check = new PdfDocument(this, local)) {
                check.count();
              }
            } else if (ext.equals("xls") || ext.equals("xlsx")) {
              try (Spreadsheet check = new Spreadsheet(local, ext.equals("xls"))) {
                if (check.names.isEmpty()) throw new IOException("빈 워크북입니다.");
              }
            } else new HangulDocument(local, ext.equals("hwpx"));
            String finalDisplay = display;
            history.start(finalDisplay, ext, target -> copy(local, target));
            name = finalDisplay;
            extension = ext;
            load(history.current());
            resetPosition();
            dirty = false;
          } finally {
            local.delete();
          }
        },
        this::show);
  }

  private void restore() {
    job(
        "자동저장 문서 복구 중…",
        () -> {
          name = history.name();
          extension = history.extension();
          load(history.current());
          resetPosition();
          dirty = true;
        },
        this::show);
  }

  private void resetPosition() {
    page = layoutPage = blockStart = sheet = firstRow = firstCol = 0;
    layoutMode = false;
  }

  private void load(File from) throws Exception {
    File local = File.createTempFile("working-", "." + extension, getCacheDir());
    copy(from, local);
    HangulDocument h = null;
    Spreadsheet e = null;
    PdfDocument p = null;
    try {
      if (extension.equals("hwp") || extension.equals("hwpx"))
        h = new HangulDocument(local, extension.equals("hwpx"));
      else if (extension.equals("pdf")) p = new PdfDocument(this, local);
      else e = new Spreadsheet(local, extension.equals("xls"));
    } catch (Exception failure) {
      local.delete();
      throw failure;
    }
    closeModel();
    input = local;
    hangul = h;
    excel = e;
    pdf = p;
    if (pdf != null) page = Math.max(0, Math.min(page, pdf.count() - 1));
    if (hangul != null)
      blockStart = Math.max(0, Math.min(blockStart, Math.max(0, hangul.blocks.size() - 1)));
  }

  private void writeCurrent(File target) throws Exception {
    if (hangul != null) {
      hangul.save(target);
      HangulDocument check = new HangulDocument(target, extension.equals("hwpx"));
      if (check.blocks.size() != hangul.blocks.size()) throw new IOException("저장 후 문단 수가 달라졌습니다.");
      for (int i = 0; i < check.blocks.size(); i++)
        if (!check.blocks.get(i).text.equals(hangul.blocks.get(i).text))
          throw new IOException("저장한 텍스트를 다시 확인하지 못했습니다.");
    } else if (excel != null) {
      excel.save(target);
      try (Spreadsheet check = new Spreadsheet(target, extension.equals("xls"))) {
        if (!check.names.equals(excel.names)) throw new IOException("저장 후 시트가 달라졌습니다.");
        for (int i = 0; i < excel.names.size(); i++) {
          List<String> refs = excel.addresses(i);
          for (int n = 0; n < refs.size(); n += Math.max(1, refs.size() / 64)) {
            String ref = refs.get(n);
            if (!check.value(i, ref).equals(excel.value(i, ref)))
              throw new IOException("저장 후 셀 내용이 달라졌습니다: " + ref);
          }
        }
      }
    } else {
      pdf.save(target, marks);
      try (PdfDocument check = new PdfDocument(this, target)) {
        if (check.count() != pdf.count()) throw new IOException("저장 후 페이지 수가 달라졌습니다.");
      }
    }
  }

  private void mutate(String text, Job mutation, Runnable redraw) {
    job(
        text,
        () -> {
          try {
            mutation.run();
            history.commit(this::writeCurrent);
            load(history.current());
            dirty = true;
          } catch (Exception failure) {
            if (history.available()) load(history.current());
            throw failure;
          }
        },
        redraw,
        true);
  }

  private void travel(boolean redo) {
    if (input == null || !(redo ? history.canRedo() : history.canUndo())) {
      message("이동할 편집 기록이 없습니다.");
      return;
    }
    job(
        redo ? "다시 실행 중…" : "실행 취소 중…",
        () -> {
          load(redo ? history.redo() : history.undo());
          dirty = true;
        },
        this::show);
  }

  private void show() {
    subtitle.setText(name);
    scroll.scrollTo(0, 0);
    if (hangul != null) showHangul();
    else if (excel != null) showExcel();
    else if (pdf != null) showPdf();
    else home();
  }

  private void tools() {
    if (input == null) {
      menu(
          "작업실",
          new String[] {"새 문서", "문서 열기", "모양 설정", "지원 기능"},
          i -> {
            if (i == 0) newDocumentMenu();
            else if (i == 1) picker(OPEN, "*/*");
            else if (i == 2) appearance();
            else capabilities();
          });
      return;
    }
    menu(
        "문서 작업",
        new String[] {"편집 도구", "수정본 공유", "인쇄", "원본 별도 저장", "원본으로 되돌리기", "문서 정보", "모양 설정", "새 문서"},
        i -> {
          switch (i) {
            case 0:
              documentTools();
              break;
            case 1:
              share();
              break;
            case 2:
              print();
              break;
            case 3:
              exportPicker(ORIGINAL);
              break;
            case 4:
              new AlertDialog.Builder(this)
                  .setTitle("원본으로 되돌리기")
                  .setMessage("이 문서를 처음 열었을 때의 내용으로 돌아갑니다. 실행 취소로 현재 작업을 복구할 수 있습니다.")
                  .setNegativeButton("취소", null)
                  .setPositiveButton(
                      "되돌리기",
                      (d, w) -> mutate("원본 복구 중…", () -> load(history.original()), this::show))
                  .show();
              break;
            case 5:
              capabilities();
              break;
            case 6:
              appearance();
              break;
            case 7:
              newDocumentMenu();
              break;
            default:
              break;
          }
        });
  }

  private void documentTools() {
    if (input == null) {
      message("먼저 문서를 열어 주세요.");
      return;
    }
    if (hangul != null)
      menu(
          "한글 도구",
          new String[] {"레이아웃 보기", "PDF로 내보내기", "찾기·바꾸기", "자동저장 기록 삭제"},
          index -> {
            if (index == 0) preview();
            else if (index == 1) exportPicker(EXPORT_PDF);
            else if (index == 2) replaceDialog();
            else clearRecovery();
          });
    else if (excel != null)
      menu(
          "엑셀 도구",
          new String[] {
            "찾기·바꾸기",
            "현재 시트에서 찾기",
            "범위 복사·붙여넣기·서식",
            "함수 사전",
            "새 시트 추가",
            "다시 계산",
            "현재 시트 CSV 저장",
            "현재 시트 PDF 저장",
            "자동저장 기록 삭제"
          },
          index -> {
            if (index == 0) replaceDialog();
            else if (index == 1) searchSheet();
            else if (index == 2) rangeTools();
            else if (index == 3) functionHelp();
            else if (index == 4)
              edit(
                  "새 시트 이름",
                  "Sheet" + (excel.names.size() + 1),
                  false,
                  value ->
                      mutate(
                          "시트 추가 중…",
                          () -> {
                            excel.addSheet(value);
                            sheet = excel.names.size() - 1;
                            firstRow = firstCol = 0;
                          },
                          this::show));
            else if (index == 5) {
              excel.recalculate();
              showExcel();
            } else if (index == 6) exportPicker(CSV);
            else if (index == 7) exportPicker(EXPORT_PDF);
            else clearRecovery();
          });
    else
      menu(
          "PDF 도구",
          new String[] {
            "서명 이미지 넣기",
            "현재 페이지 90° 회전",
            "현재 페이지 삭제",
            "페이지 순서 변경",
            "다른 PDF 합치기",
            "페이지 추출·분할",
            "텍스트 찾기",
            "현재 페이지 글자 복사",
            "펜 색·두께",
            "자동저장 기록 삭제"
          },
          index -> {
            if (index == 0) picker(SIGNATURE, "image/*");
            else if (index == 1)
              mutate(
                  "페이지 회전 중…",
                  () -> {
                    flattenPdf();
                    pdf.rotate(page);
                  },
                  this::show);
            else if (index == 2)
              new AlertDialog.Builder(this)
                  .setTitle("" + (page + 1) + "페이지 삭제")
                  .setMessage("실행 취소로 복구할 수 있습니다.")
                  .setNegativeButton("취소", null)
                  .setPositiveButton(
                      "삭제",
                      (d, w) ->
                          mutate(
                              "페이지 삭제 중…",
                              () -> {
                                flattenPdf();
                                pdf.delete(page);
                              },
                              this::show))
                  .show();
            else if (index == 3)
              edit(
                  "새 순서 (예: 3,1,2 또는 3-1)",
                  "1-" + pdf.count(),
                  false,
                  value ->
                      mutate(
                          "순서 변경 중…",
                          () -> {
                            List<Integer> order = PageRanges.parse(value, pdf.count(), true);
                            flattenPdf();
                            pdf.reorder(order);
                            page = 0;
                          },
                          this::show));
            else if (index == 4) picker(MERGE, "application/pdf");
            else if (index == 5)
              edit(
                  "내보낼 페이지 (예: 1-3,5)",
                  "" + (page + 1),
                  false,
                  value -> {
                    try {
                      extraction = PageRanges.parse(value, pdf.count(), false);
                      exportPicker(EXTRACT);
                    } catch (Exception e) {
                      error(e);
                    }
                  });
            else if (index == 6) searchPdf();
            else if (index == 7)
              job(
                  "PDF 글자 읽는 중…",
                  () -> {
                    String value = pdf.text(page);
                    runOnUiThread(
                        () -> {
                          if (!destroyed) clipboard(value);
                        });
                  },
                  () -> {});
            else if (index == 8) penSettings();
            else clearRecovery();
          });
  }

  private interface Choice {
    void choose(int index);
  }

  private void menu(String title, String[] items, Choice action) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setItems(
            items,
            (d, i) -> {
              if (!busy) action.choose(i);
            })
        .show();
  }

  private void clearRecovery() {
    new AlertDialog.Builder(this)
        .setTitle("자동저장 기록 삭제")
        .setMessage("문서를 닫고 저장된 복구·변경 이력을 삭제합니다. 내보낸 파일은 유지됩니다.")
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "삭제",
            (d, w) ->
                job(
                    "삭제 중…",
                    () -> {
                      closeModel();
                      history.clear();
                      dirty = false;
                    },
                    () -> {
                      subtitle.setText("내 문서, 내 휴대폰에서");
                      home();
                    }))
        .show();
  }

  private void replaceDialog() {
    LinearLayout fields = vertical();
    EditText find = field("찾을 내용", ""), replacement = field("바꿀 내용", "");
    fields.addView(find);
    fields.addView(replacement);
    new AlertDialog.Builder(this)
        .setTitle("모두 바꾸기")
        .setView(fields)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "적용",
            (d, w) -> {
              int[] changed = {0};
              mutate(
                  "내용 바꾸는 중…",
                  () ->
                      changed[0] =
                          hangul != null
                              ? hangul.replace(
                                  find.getText().toString(), replacement.getText().toString())
                              : excel.replace(
                                  find.getText().toString(), replacement.getText().toString()),
                  () -> {
                    show();
                    message(changed[0] + "개 항목을 수정했습니다.");
                  });
            })
        .show();
  }

  private void showHangul() {
    layoutMode = false;
    content.removeAllViews();
    LinearLayout nav = horizontal();
    content.addView(nav);
    nav.addView(
        button(
            "이전",
            () -> {
              blockStart = Math.max(0, blockStart - 40);
              showHangul();
            }));
    nav.addView(
        button(
            "다음",
            () -> {
              if (blockStart + 40 < hangul.blocks.size()) blockStart += 40;
              showHangul();
            }));
    nav.addView(button("찾기", this::searchHangul));
    nav.addView(button("레이아웃", this::preview));
    int end = Math.min(blockStart + 40, hangul.blocks.size());
    content.addView(
        label(
            (blockStart + 1) + "–" + end + " / " + hangul.blocks.size() + " · 문단을 눌러 편집",
            12,
            green));
    for (int index = blockStart; index < end; index++) {
      TextBlock block = hangul.blocks.get(index);
      LinearLayout card = vertical();
      card.setPadding(dp(12), dp(8), dp(12), dp(8));
      card.setBackground(round(surface, 18));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.bottomMargin = dp(8);
      content.addView(card, params);
      card.addView(label(block.label + (block.editable ? "" : " · 읽기 전용"), 11, green));
      TextView text = label(block.text.isEmpty() ? "(빈 문단)" : block.text, 17, ink);
      card.addView(text);
      try {
        DocumentStyle style = hangul.style(block);
        text.setTextSize(style.size + 3);
        text.setTypeface(Typeface.create(style.font, style.bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setGravity(
            style.alignment.equals("center")
                ? Gravity.CENTER_HORIZONTAL
                : style.alignment.equals("right") ? Gravity.END : Gravity.START);
      } catch (Exception ignored) {
      }
      card.setOnClickListener(
          v -> {
            if (!busy && block.editable)
              menu(
                  "문단 편집",
                  new String[] {"텍스트 수정", "글꼴·크기·굵게·정렬", "아래에 문단 추가", "문단 삭제"},
                  choice -> {
                    if (choice == 0)
                      edit(
                          "텍스트 수정",
                          block.text,
                          false,
                          value -> mutate("수정 중…", () -> block.set(value), this::show));
                    else if (choice == 1) {
                      try {
                        styleDialog(
                            hangul.style(block),
                            false,
                            style ->
                                mutate("서식 적용 중…", () -> hangul.format(block, style), this::show));
                      } catch (Exception e) {
                        error(e);
                      }
                    } else if (choice == 2)
                      edit(
                          "새 문단 내용",
                          "",
                          false,
                          value ->
                              mutate(
                                  "문단 추가 중…", () -> hangul.insertAfter(block, value), this::show));
                    else mutate("문단 삭제 중…", () -> hangul.deleteParagraph(block), this::show);
                  });
          });
    }
  }

  private void searchHangul() {
    edit(
        "문서에서 찾기",
        "",
        false,
        query -> {
          if (query.isEmpty()) return;
          for (int offset = 1; offset <= hangul.blocks.size(); offset++) {
            int i = (blockStart + offset) % hangul.blocks.size();
            if (hangul.blocks.get(i).text.contains(query)) {
              blockStart = i;
              showHangul();
              scroll.scrollTo(0, 0);
              return;
            }
          }
          message("찾는 내용이 없습니다.");
        });
  }

  private void preview() {
    job(
        "문서 레이아웃 만드는 중…",
        () -> {
          prepareLayout();
          layoutMode = true;
          layoutPage = 0;
        },
        this::showLayout);
  }

  private void prepareLayout() throws Exception {
    if (layoutPdf != null) layoutPdf.close();
    if (layoutFile != null) layoutFile.delete();
    layoutFile = File.createTempFile("layout-", ".pdf", getCacheDir());
    HangulRenderer renderer = new HangulRenderer(hangul);
    renderer.export(layoutFile);
    layoutNotice =
        renderer.notices.isEmpty()
            ? "기기 글꼴로 배치합니다. 한컴의 인쇄 배치와 차이가 날 수 있습니다."
            : android.text.TextUtils.join("\n", renderer.notices);
    layoutPdf = new PdfDocument(this, layoutFile);
  }

  private void showLayout() {
    job(
        "레이아웃 페이지 표시 중…",
        () -> {
          Bitmap next = layoutPdf.render(layoutPage);
          runOnUiThread(
              () -> {
                if (destroyed) {
                  next.recycle();
                  return;
                }
                setBitmap(next);
                content.removeAllViews();
                LinearLayout nav = horizontal();
                content.addView(nav);
                nav.addView(button("텍스트", this::showHangul));
                nav.addView(
                    button(
                        "이전",
                        () -> {
                          if (layoutPage > 0) {
                            layoutPage--;
                            showLayout();
                          }
                        }));
                nav.addView(
                    button(
                        "다음",
                        () -> {
                          if (layoutPage + 1 < layoutPdf.count()) {
                            layoutPage++;
                            showLayout();
                          }
                        }));
                nav.addView(button("PDF 저장", () -> exportPicker(EXPORT_PDF)));
                content.addView(
                    label("레이아웃 " + (layoutPage + 1) + " / " + layoutPdf.count(), 12, muted));
                content.addView(label(layoutNotice, 12, muted));
                ImageView image = new ImageView(this);
                image.setAdjustViewBounds(true);
                image.setImageBitmap(next);
                addPage(image);
              });
        },
        () -> {});
  }

  private void showExcel() {
    job(
        "시트 계산 중…",
        () -> {
          List<String[]> rows = new ArrayList<>();
          List<DocumentStyle[]> styles = new ArrayList<>();
          for (int row = firstRow;
              row < Math.min(firstRow + 20, extension.equals("xls") ? 65536 : 1048576);
              row++) {
            String[] values = new String[5];
            DocumentStyle[] formats = new DocumentStyle[5];
            for (int col = firstCol;
                col < Math.min(firstCol + 5, extension.equals("xls") ? 256 : 16384);
                col++) {
              String address = Spreadsheet.address(col, row);
              values[col - firstCol] = excel.display(sheet, address);
              formats[col - firstCol] = excel.style(sheet, address);
            }
            rows.add(values);
            styles.add(formats);
          }
          runOnUiThread(
              () -> {
                if (!destroyed) drawSheet(rows, styles);
              });
        },
        () -> {});
  }

  private void drawSheet(List<String[]> rows, List<DocumentStyle[]> styles) {
    content.removeAllViews();
    Spinner spinner = new Spinner(this);
    spinner.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, excel.names));
    spinner.setSelection(sheet);
    content.addView(spinner);
    spinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> parent) {}

          public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (!busy && sheet != position) {
              sheet = position;
              firstRow = firstCol = 0;
              showExcel();
            }
          }
        });
    LinearLayout nav = horizontal();
    content.addView(nav);
    nav.addView(
        button(
            "←",
            () -> {
              firstCol = Math.max(0, firstCol - 5);
              showExcel();
            }));
    nav.addView(
        button(
            "→",
            () -> {
              firstCol = Math.min(extension.equals("xls") ? 251 : 16379, firstCol + 5);
              showExcel();
            }));
    nav.addView(button("셀 이동", () -> edit("셀 주소", "A1", false, address -> jump(address))));
    nav.addView(button("찾기", this::searchSheet));
    LinearLayout verticalNav = horizontal();
    content.addView(verticalNav);
    verticalNav.addView(
        button(
            "이전 20행",
            () -> {
              firstRow = Math.max(0, firstRow - 20);
              showExcel();
            }));
    verticalNav.addView(
        button(
            "다음 20행",
            () -> {
              firstRow = Math.min(extension.equals("xls") ? 65516 : 1048556, firstRow + 20);
              showExcel();
            }));
    content.addView(label("셀을 눌러 값·수식·서식을 편집하세요", 12, muted));
    HorizontalScrollView area = new HorizontalScrollView(this);
    TableLayout table = new TableLayout(this);
    area.addView(table);
    content.addView(area);
    TableRow headings = new TableRow(this);
    table.addView(headings);
    TextView corner = label("", 12, muted);
    corner.setWidth(dp(36));
    headings.addView(corner);
    for (int col = 0; col < 5; col++) {
      TextView heading =
          label(Spreadsheet.address(firstCol + col, 0).replaceAll("[0-9]", ""), 12, muted);
      heading.setWidth(dp(125));
      heading.setGravity(Gravity.CENTER);
      heading.setTypeface(null, Typeface.BOLD);
      headings.addView(heading);
    }
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      TableRow row = new TableRow(this);
      table.addView(row);
      TextView number = label("" + (firstRow + rowIndex + 1), 12, muted);
      number.setWidth(dp(36));
      number.setGravity(Gravity.CENTER);
      row.addView(number);
      for (int colIndex = 0; colIndex < 5; colIndex++) {
        if (rows.get(rowIndex)[colIndex] == null) continue;
        String address = Spreadsheet.address(firstCol + colIndex, firstRow + rowIndex);
        DocumentStyle style = styles.get(rowIndex)[colIndex];
        TextView cell = label(rows.get(rowIndex)[colIndex], Math.round(style.size + 2), ink);
        cell.setWidth(dp(125));
        cell.setMinHeight(dp(52));
        cell.setPadding(dp(10), dp(12), dp(10), dp(12));
        cell.setMaxLines(2);
        cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.setFocusable(true);
        cell.setTypeface(Typeface.create(style.font, style.bold ? Typeface.BOLD : Typeface.NORMAL));
        cell.setGravity(
            Gravity.CENTER_VERTICAL
                | (style.alignment.equals("right")
                    ? Gravity.END
                    : style.alignment.equals("center")
                        ? Gravity.CENTER_HORIZONTAL
                        : Gravity.START));
        GradientDrawable bg = round(surface, 0);
        bg.setStroke(dp(1), border);
        cell.setBackground(bg);
        row.addView(cell);
        cell.setContentDescription(address + " " + rows.get(rowIndex)[colIndex]);
        cell.setOnClickListener(
            v -> {
              if (!busy)
                menu(
                    address,
                    new String[] {"값·수식 수정", "글꼴·정렬·표시 형식"},
                    choice -> {
                      if (choice == 0)
                        job(
                            "셀 원문 읽는 중…",
                            () -> {
                              String raw = excel.value(sheet, address);
                              runOnUiThread(
                                  () -> {
                                    if (!destroyed)
                                      edit(
                                          address + " 수정",
                                          raw,
                                          true,
                                          value ->
                                              mutate(
                                                  "셀 수정 중…",
                                                  () -> excel.set(sheet, address, value),
                                                  this::show));
                                  });
                            },
                            () -> {});
                      else
                        styleDialog(
                            style,
                            true,
                            format ->
                                mutate(
                                    "셀 서식 적용 중…",
                                    () -> excel.format(sheet, address, format),
                                    this::show));
                    });
            });
      }
    }
  }

  private void jump(String address) {
    try {
      int[] pos = Spreadsheet.coordinate(address.trim());
      if (extension.equals("xls") && (pos[0] > 255 || pos[1] > 65535))
        throw new IOException("XLS 범위를 벗어났습니다.");
      firstCol = pos[0];
      firstRow = pos[1];
      showExcel();
    } catch (Exception e) {
      error(e);
    }
  }

  private void searchSheet() {
    edit(
        "현재 시트에서 찾기",
        "",
        false,
        query -> {
          String[] found = {null};
          job(
              "셀 찾는 중…",
              () -> {
                for (String address : excel.addresses(sheet))
                  if (excel.value(sheet, address).contains(query)) {
                    found[0] = address;
                    break;
                  }
              },
              () -> {
                if (found[0] != null) jump(found[0]);
                else message("찾는 내용이 없습니다.");
              });
        });
  }

  private void showPdf() {
    job(
        "PDF 표시 중…",
        () -> {
          Bitmap next = pdf.render(page);
          runOnUiThread(
              () -> {
                if (destroyed) {
                  next.recycle();
                  return;
                }
                setBitmap(next);
                drawPdf();
              });
        },
        () -> {});
  }

  private void drawPdf() {
    content.removeAllViews();
    LinearLayout nav = horizontal();
    content.addView(nav);
    nav.addView(
        button(
            "이전",
            () -> {
              if (page > 0) {
                page--;
                showPdf();
              }
            }));
    nav.addView(
        button(
            "다음",
            () -> {
              if (page + 1 < pdf.count()) {
                page++;
                showPdf();
              }
            }));
    nav.addView(
        button(
            "페이지",
            () ->
                edit(
                    "페이지 번호",
                    "" + (page + 1),
                    false,
                    value -> {
                      try {
                        int n = Integer.parseInt(value);
                        if (n < 1 || n > pdf.count()) throw new IOException("페이지 범위를 벗어났습니다.");
                        page = n - 1;
                        showPdf();
                      } catch (Exception e) {
                        error(e);
                      }
                    })));
    content.addView(label((page + 1) + " / " + pdf.count() + " 페이지 · 도구에서 서명·페이지 관리", 12, green));
    LinearLayout tools = horizontal();
    content.addView(tools);
    Button pen = button("펜 켜기", () -> {});
    tools.addView(pen);
    pen.setOnClickListener(
        v -> {
          if (!busy) {
            pageCanvas.ink = !pageCanvas.ink;
            inkMode = pageCanvas.ink;
            pageCanvas.pendingText = null;
            pageCanvas.pendingImage = null;
            pen.setText(pageCanvas.ink ? "펜 끄기" : "펜 켜기");
          }
        });
    tools.addView(
        button(
            "글자",
            () ->
                edit(
                    "추가할 글자",
                    "",
                    false,
                    value -> {
                      if (!value.isEmpty()) {
                        pageCanvas.pendingText = value;
                        pageCanvas.pendingImage = null;
                        pageCanvas.ink = false;
                        inkMode = false;
                        pen.setText("펜 켜기");
                        message("넣을 위치를 누르세요.");
                      }
                    })));
    tools.addView(button("서명", () -> picker(SIGNATURE, "image/*")));
    content.addView(label("한 번의 필기·삽입마다 자동 저장합니다. 펜을 끄면 스크롤할 수 있습니다.", 12, green));
    pageCanvas =
        new PageCanvas(
            this,
            pageBitmap,
            marks.computeIfAbsent(page, key -> new ArrayList<>()),
            () -> mutate("필기 자동저장 중…", () -> {}, this::show));
    pageCanvas.ink = inkMode;
    pageCanvas.strokeColor = penColor;
    pageCanvas.strokeWidth = penWidth;
    pen.setText(inkMode ? "펜 끄기" : "펜 켜기");
    addPage(pageCanvas);
  }

  private void addPage(View view) {
    ZoomPage area = new ZoomPage(this, view, zoom, value -> zoom = value);
    LinearLayout nav = horizontal();
    TextView amount = label(Math.round(zoom * 100) + "% · 두 손가락으로 확대", 12, muted);
    nav.addView(
        button(
            "축소",
            () -> {
              area.setZoom(zoom - .25f);
              amount.setText(Math.round(zoom * 100) + "% · 두 손가락으로 확대");
            }));
    nav.addView(
        button(
            "확대",
            () -> {
              area.setZoom(zoom + .25f);
              amount.setText(Math.round(zoom * 100) + "% · 두 손가락으로 확대");
            }));
    nav.addView(
        button(
            "맞춤",
            () -> {
              area.setZoom(1);
              amount.setText("100% · 두 손가락으로 확대");
            }));
    content.addView(nav);
    content.addView(amount);
    content.addView(area, new LinearLayout.LayoutParams(-1, -2));
  }

  private void signature(Uri uri) {
    Bitmap[] bitmap = {null};
    job(
        "서명 이미지 읽는 중…",
        () -> {
          File file = importFile(uri, ".image");
          try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (options.outWidth < 1 || options.outHeight < 1)
              throw new IOException("이미지를 읽을 수 없습니다.");
            options.inJustDecodeBounds = false;
            options.inSampleSize =
                Math.max(1, (int) Math.ceil(Math.max(options.outWidth, options.outHeight) / 1200f));
            bitmap[0] = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bitmap[0] == null) throw new IOException("이미지를 읽을 수 없습니다.");
          } finally {
            file.delete();
          }
        },
        () ->
            edit(
                "서명 너비: 페이지의 % (5–90)",
                "25",
                false,
                value -> {
                  try {
                    float ratio = Float.parseFloat(value) / 100;
                    if (!Float.isFinite(ratio) || ratio < .05 || ratio > .9)
                      throw new IOException("5–90 사이의 값을 입력하세요.");
                    pageCanvas.signatureWidth = ratio;
                    pageCanvas.pendingImage = bitmap[0];
                    pageCanvas.ink = false;
                    inkMode = false;
                    pageCanvas.pendingText = null;
                    message("페이지에서 서명을 넣을 위치를 누르세요.");
                  } catch (Exception e) {
                    bitmap[0].recycle();
                    error(e);
                  }
                }));
  }

  private void flattenPdf() throws Exception {
    if (marks.isEmpty()) return;
    File file = File.createTempFile("flatten-", ".pdf", getCacheDir());
    try {
      pdf.save(file, marks);
      PdfDocument next = new PdfDocument(this, file);
      pdf.close();
      pdf = next;
      marks.clear();
    } finally {
      file.delete();
    }
  }

  private void export(Uri uri, int request) {
    job(
        "파일 내보내는 중…",
        () -> {
          File output =
              File.createTempFile(
                  "export-",
                  request == SAVE || request == ORIGINAL
                      ? "." + extension
                      : request == CSV ? ".csv" : ".pdf",
                  getCacheDir());
          try {
            if (request == SAVE) writeCurrent(output);
            else if (request == ORIGINAL) copy(history.original(), output);
            else if (request == EXPORT_PDF) writePdf(output);
            else if (request == CSV) {
              try (OutputStream out = new FileOutputStream(output)) {
                out.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
                out.write(excelCsv().getBytes(java.nio.charset.StandardCharsets.UTF_8));
              }
            } else {
              flattenPdf();
              pdf.extract(output, extraction);
            }
            try (InputStream in = new FileInputStream(output);
                OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException("저장 위치에 쓸 수 없습니다.");
              transfer(in, out);
            }
            // Verify the provider accepted every byte before reporting success.
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              if (in == null) throw new IOException("저장한 파일을 읽을 수 없습니다.");
              java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256"),
                  expected = java.security.MessageDigest.getInstance("SHA-256");
              byte[] b = new byte[8192];
              int n;
              while ((n = in.read(b)) != -1) hash.update(b, 0, n);
              try (InputStream source = new FileInputStream(output)) {
                while ((n = source.read(b)) != -1) expected.update(b, 0, n);
              }
              if (!Arrays.equals(hash.digest(), expected.digest()))
                throw new IOException("저장한 파일의 내용이 일치하지 않습니다. 다른 저장 위치를 선택해 주세요.");
            }
            if (request == SAVE) dirty = false;
          } finally {
            output.delete();
          }
        },
        () -> {
          message("파일을 저장했습니다.");
          updateStatus();
        });
  }

  private String excelCsv() throws Exception {
    int maxRow = 0, maxCol = 0;
    for (String ref : excel.addresses(sheet)) {
      int[] p = Spreadsheet.coordinate(ref);
      maxRow = Math.max(maxRow, p[1]);
      maxCol = Math.max(maxCol, p[0]);
    }
    if ((long) (maxRow + 1) * (maxCol + 1) > 100000)
      throw new IOException("CSV 내보내기 범위는 100,000셀까지 지원합니다.");
    StringBuilder text = new StringBuilder();
    for (int row = 0; row <= maxRow; row++) {
      for (int col = 0; col <= maxCol; col++) {
        if (col > 0) text.append(',');
        text.append('"')
            .append(excel.display(sheet, Spreadsheet.address(col, row)).replace("\"", "\"\""))
            .append('"');
      }
      text.append("\r\n");
      if (text.length() > 16 * 1024 * 1024) throw new IOException("CSV 내용이 너무 큽니다.");
    }
    return text.toString();
  }

  private interface StyleAction {
    void accept(DocumentStyle style);
  }

  private void styleDialog(DocumentStyle initial, boolean numbers, StyleAction action) {
    LinearLayout form = vertical();
    form.setPadding(dp(16), 0, dp(16), dp(8));
    EditText font = field("글꼴 이름", initial.font), size = field("크기(pt)", "" + initial.size);
    size.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    form.addView(font);
    form.addView(size);
    CheckBox bold = new CheckBox(this);
    bold.setText("굵게");
    bold.setChecked(initial.bold);
    form.addView(bold);
    Spinner alignment = new Spinner(this);
    String[] aligns = {"left", "center", "right"};
    alignment.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"왼쪽", "가운데", "오른쪽"}));
    alignment.setSelection(Arrays.asList(aligns).indexOf(initial.alignment));
    form.addView(alignment);
    Spinner format = new Spinner(this);
    String[] formats = {"general", "number", "currency", "date", "percent"};
    if (numbers) {
      format.setAdapter(
          new ArrayAdapter<>(
              this,
              android.R.layout.simple_spinner_dropdown_item,
              new String[] {"일반", "숫자", "금액", "날짜", "백분율"}));
      format.setSelection(Arrays.asList(formats).indexOf(initial.numberFormat));
      form.addView(format);
    }
    ScrollView container = new ScrollView(this);
    container.addView(form);
    new AlertDialog.Builder(this)
        .setTitle("서식 편집")
        .setView(container)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "적용",
            (d, w) -> {
              try {
                DocumentStyle style = initial.copy();
                style.font = font.getText().toString().trim();
                style.size = Float.parseFloat(size.getText().toString());
                style.bold = bold.isChecked();
                style.alignment = aligns[alignment.getSelectedItemPosition()];
                if (numbers) style.numberFormat = formats[format.getSelectedItemPosition()];
                style.validate();
                action.accept(style);
              } catch (Exception e) {
                error(e);
              }
            })
        .show();
  }

  private EditText field(String hint, String value) {
    EditText v = new EditText(this);
    v.setHint(hint);
    v.setText(value);
    v.setSingleLine(true);
    v.setPadding(dp(16), dp(8), dp(16), dp(8));
    return v;
  }

  private void edit(String title, String value, boolean multiline, TextAction action) {
    EditText v = field(title, value);
    v.setInputType(
        InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    v.setSingleLine(!multiline);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(v)
        .setNegativeButton("취소", null)
        .setPositiveButton(
            "적용",
            (d, w) -> {
              if (!busy) action.accept(v.getText().toString());
            })
        .show();
  }

  private void guard(Runnable next) {
    if (!dirty) {
      next.run();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("다른 문서 열기")
        .setMessage("현재 내용은 자동저장되어 있습니다. 새 문서를 열면 복구 기록이 새 문서로 바뀝니다. 필요한 수정본은 먼저 저장하세요.")
        .setNegativeButton("돌아가기", null)
        .setPositiveButton("계속", (d, w) -> next.run())
        .show();
  }

  private void job(String text, Job action, Runnable after) {
    job(text, action, after, false);
  }

  private void job(String text, Job action, Runnable after, boolean refreshOnFailure) {
    if (busy || destroyed) return;
    busy = true;
    status.setText(text);
    progress.setVisibility(View.VISIBLE);
    enable(root, false);
    worker.execute(
        () -> {
          Exception failure = null;
          try {
            action.run();
          } catch (Exception e) {
            failure = e;
          }
          final Exception result = failure;
          runOnUiThread(
              () -> {
                if (destroyed) return;
                busy = false;
                progress.setVisibility(View.GONE);
                enable(root, true);
                if (rebuildPending) {
                  rebuildPending = false;
                  buildShell();
                }
                updateStatus();
                if (result == null) after.run();
                else {
                  error(result);
                  if (refreshOnFailure && input != null) show();
                }
              });
        });
  }

  private void updateStatus() {
    if (status == null) return;
    status.setText(
        input == null
            ? "HWP · HWPX · PDF · XLSX · XLS"
            : extension.toUpperCase(Locale.ROOT) + (dirty ? " · 수정됨 · 자동저장 완료" : " · 자동저장 완료"));
    if (undoButton != null) {
      undoButton.setEnabled(!busy && history != null && history.canUndo());
      redoButton.setEnabled(!busy && history != null && history.canRedo());
      saveButton.setEnabled(!busy && input != null);
    }
  }

  private void enable(View view, boolean value) {
    view.setEnabled(value);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), value);
    }
  }

  private void message(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }

  private void error(Exception e) {
    new AlertDialog.Builder(this)
        .setTitle("작업을 완료하지 못했습니다")
        .setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
        .setPositiveButton("확인", null)
        .show();
  }

  private void setBitmap(Bitmap next) {
    content.removeAllViews();
    if (pageBitmap != null) pageBitmap.recycle();
    pageBitmap = next;
  }

  private void closeModel() {
    if (excel != null) excel.close();
    if (pdf != null) pdf.close();
    if (layoutPdf != null) layoutPdf.close();
    if (input != null) input.delete();
    if (layoutFile != null) layoutFile.delete();
    hangul = null;
    excel = null;
    pdf = layoutPdf = null;
    input = layoutFile = null;
    marks.clear();
    pageCanvas = null;
    layoutMode = false;
  }

  private static void copy(File from, File to) throws IOException {
    try (InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to)) {
      transfer(in, out);
    }
  }

  private static void transfer(InputStream in, OutputStream out) throws IOException {
    byte[] b = new byte[8192];
    int n;
    while ((n = in.read(b)) != -1) out.write(b, 0, n);
    out.flush();
  }

  @Override
  public void onBackPressed() {
    if (busy) return;
    if (layoutMode) {
      showHangul();
      return;
    }
    finish();
  }

  @Override
  public void onConfigurationChanged(Configuration config) {
    super.onConfigurationChanged(config);
    if (busy) {
      rebuildPending = true;
      return;
    }
    boolean preview = layoutMode;
    buildShell();
    if (preview && layoutPdf != null) {
      layoutMode = true;
      showLayout();
    } else if (input != null) show();
    else home();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("active", input != null);
    state.putBoolean("dirty", dirty);
    state.putInt("page", page);
    state.putInt("sheet", sheet);
    state.putInt("row", firstRow);
    state.putInt("col", firstCol);
    state.putInt("block", blockStart);
    state.putFloat("zoom", zoom);
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    worker.execute(this::closeModel);
    worker.shutdown();
    if (pageBitmap != null) pageBitmap.recycle();
    super.onDestroy();
  }
}
