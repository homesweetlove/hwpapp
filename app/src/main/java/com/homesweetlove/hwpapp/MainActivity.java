package com.homesweetlove.hwpapp;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.print.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
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
  private int ink, accent, background, surface, muted, border, chrome, fill, well;
  private FrameLayout stage;
  private TextView typePill, hud;
  private int topInset, readingWidth = -1, turn, pendingScroll = -1;
  private int flashBlock = -1, flashSearch = -1, lastSheet = -1;
  private boolean revealFlash;
  private String lastScreen = "", flashCell;
  private boolean dark, wide;
  private Button undoButton, redoButton, saveButton, toolsButton;
  private ProgressBar progress;
  private float zoom = 1f;
  private int penColor = 0xff007aff;
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
    // macOS system palette: soft window canvas, white sheets, Apple blue accent.
    ink = Color.parseColor(dark ? "#F5F5F7" : "#1D1D1F");
    accent = Color.parseColor(dark ? "#0A84FF" : "#007AFF");
    background = Color.parseColor(dark ? "#161618" : "#F5F5F7");
    surface = Color.parseColor(dark ? "#242427" : "#FFFFFF");
    muted = Color.parseColor(dark ? "#A1A1A6" : "#6E6E73");
    border = Color.parseColor(dark ? "#3A3A3E" : "#DCDCE1");
    chrome = Color.parseColor(dark ? "#1E1E21" : "#ECECF0");
    fill = Color.parseColor(dark ? "#323236" : "#E8E8ED");
    well = Color.parseColor(dark ? "#1C1C1E" : "#F2F2F7");
  }

  private int typeColor(String ext) {
    switch (ext) {
      case "xlsx":
      case "xls":
        return Color.parseColor(dark ? "#30D158" : "#34C759");
      case "pdf":
        return Color.parseColor(dark ? "#FF453A" : "#FF3B30");
      case "hwp":
      case "hwpx":
        return accent;
      default:
        return Color.parseColor("#8E8E93");
    }
  }

  private static int alpha(int color, int alpha) {
    return (color & 0x00ffffff) | (alpha << 24);
  }

  private static int blend(int from, int to, float amount) {
    return Color.rgb(
        Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
        Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
        Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
  }

  private void buildShell() {
    wide = getResources().getConfiguration().screenWidthDp >= 840;
    stage = new FrameLayout(this);
    stage.setBackgroundColor(background);
    root = vertical();
    root.setBackgroundColor(background);
    stage.addView(root, new FrameLayout.LayoutParams(-1, -1));
    setContentView(stage);
    getWindow().setStatusBarColor(background);
    getWindow().setNavigationBarColor(background);
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
          topInset = top;
          return insets;
        });
    root.requestApplyInsets();

    // Title bar: traffic lights, app badge, document name and its type.
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(18), dp(10), dp(18), dp(10));
    header.addView(trafficLights());
    ImageView badge = new ImageView(this);
    badge.setImageResource(R.drawable.ic_app);
    badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(34), dp(34));
    badgeParams.setMarginStart(dp(14));
    header.addView(badge, badgeParams);
    LinearLayout titles = vertical();
    titles.setPadding(dp(10), 0, dp(8), 0);
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    TextView title = label("HWP Desk", 17, ink);
    title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    title.setPadding(0, 0, 0, 0);
    titles.addView(title);
    subtitle = label(input == null ? "내 문서를 위한 작은 작업실" : name, 12, muted);
    subtitle.setPadding(0, 0, 0, 0);
    subtitle.setMaxLines(1);
    subtitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    titles.addView(subtitle);
    typePill = label("", 11, Color.WHITE);
    typePill.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    typePill.setPadding(dp(9), dp(3), dp(9), dp(3));
    typePill.setVisibility(View.GONE);
    header.addView(typePill);
    root.addView(header);
    View rule = new View(this);
    rule.setBackgroundColor(border);
    root.addView(rule, new LinearLayout.LayoutParams(-1, 1));
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
    progress.setVisibility(View.GONE);
    root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.HORIZONTAL);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    if (wide) {
      // Finder-style sidebar.
      LinearLayout rail = vertical();
      rail.setPadding(dp(12), dp(14), dp(12), dp(18));
      rail.setBackgroundColor(chrome);
      body.addView(rail, new LinearLayout.LayoutParams(dp(220), -1));
      rail.addView(section("작업 공간"));
      rail.addView(
          sidebarItem(R.drawable.ic_open, "문서 열기", () -> guard(() -> picker(OPEN, "*/*"))));
      rail.addView(sidebarItem(R.drawable.ic_add, "새 문서", this::newDocumentMenu));
      rail.addView(section("설정"));
      rail.addView(sidebarItem(R.drawable.ic_tools, "문서 도구", this::tools));
      rail.addView(sidebarItem(R.drawable.ic_appearance, "모양 설정", this::appearance));
      rail.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
      rail.addView(label("오프라인으로\n차분하게 작업하세요.", 13, muted));
      View divider = new View(this);
      divider.setBackgroundColor(border);
      body.addView(divider, new LinearLayout.LayoutParams(1, -1));
    }
    FrameLayout host = new FrameLayout(this);
    body.addView(host, new LinearLayout.LayoutParams(0, -1, 1));
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setPadding(dp(wide ? 28 : 16), dp(14), dp(wide ? 28 : 16), dp(128));
    int scrollWidth =
        wide ? Math.min(dp(1100), getResources().getDisplayMetrics().widthPixels - dp(221)) : -1;
    readingWidth = wide ? Math.min(dp(780), scrollWidth - dp(56)) : -1;
    host.addView(scroll, new FrameLayout.LayoutParams(scrollWidth, -1, Gravity.CENTER_HORIZONTAL));
    content = vertical();
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

    // Floating Dock.
    LinearLayout dock = vertical();
    GradientDrawable dockShape = round(alpha(surface, 0xF2), 24);
    dockShape.setStroke(1, alpha(border, 0xCC));
    dock.setBackground(dockShape);
    dock.setElevation(dp(14));
    dock.setPadding(dp(6), dp(4), dp(6), dp(5));
    FrameLayout.LayoutParams dockParams =
        new FrameLayout.LayoutParams(
            wide ? dp(560) : -1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    dockParams.setMargins(dp(12), 0, dp(12), dp(10));
    host.addView(dock, dockParams);
    LinearLayout actions = new LinearLayout(this);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    dock.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    actions.addView(
        dockButton(R.drawable.ic_open, "열기", () -> guard(() -> picker(OPEN, "*/*"))),
        new LinearLayout.LayoutParams(0, -2, 1));
    saveButton = dockButton(R.drawable.ic_save, "저장", () -> exportPicker(SAVE));
    actions.addView(saveButton, new LinearLayout.LayoutParams(0, -2, 1));
    undoButton = dockButton(R.drawable.ic_undo, "되돌리기", () -> travel(false));
    undoButton.setContentDescription("실행 취소");
    actions.addView(undoButton, new LinearLayout.LayoutParams(0, -2, 1));
    redoButton = dockButton(R.drawable.ic_redo, "다시하기", () -> travel(true));
    redoButton.setContentDescription("다시 실행");
    actions.addView(redoButton, new LinearLayout.LayoutParams(0, -2, 1));
    toolsButton = dockButton(R.drawable.ic_tools, "도구", this::tools);
    actions.addView(toolsButton, new LinearLayout.LayoutParams(0, -2, 1));
    status = label("HWP · HWPX · PDF · XLSX · XLS", 11, muted);
    status.setPadding(dp(8), 0, dp(8), dp(2));
    status.setGravity(Gravity.CENTER);
    status.setMaxLines(1);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    dock.addView(status);
    Motion.fadeIn(dock, 120);

    // Notification banner that slides down from the top, like macOS notifications.
    hud = label("", 14, ink);
    GradientDrawable hudShape = round(alpha(surface, 0xF7), 18);
    hudShape.setStroke(1, alpha(border, 0xCC));
    hud.setBackground(hudShape);
    hud.setElevation(dp(12));
    hud.setGravity(Gravity.CENTER);
    hud.setPadding(dp(18), dp(11), dp(18), dp(11));
    hud.setMaxLines(4);
    hud.setVisibility(View.GONE);
    hud.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    FrameLayout.LayoutParams hudParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    hudParams.setMargins(dp(24), dp(12), dp(24), 0);
    stage.addView(hud, hudParams);
    lastScreen = "";
    updateStatus();
  }

  private View trafficLights() {
    LinearLayout lights = new LinearLayout(this);
    lights.setOrientation(LinearLayout.HORIZONTAL);
    lights.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    for (String color : new String[] {"#FF5F57", "#FEBC2E", "#28C840"}) {
      View dot = new View(this);
      GradientDrawable shape = new GradientDrawable();
      shape.setShape(GradientDrawable.OVAL);
      int base = Color.parseColor(color);
      shape.setColor(base);
      shape.setStroke(1, blend(base, Color.BLACK, 0.18f));
      dot.setBackground(shape);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(12), dp(12));
      p.setMarginEnd(dp(8));
      lights.addView(dot, p);
    }
    return lights;
  }

  private TextView section(String text) {
    TextView v = label(text, 11, muted);
    v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    v.setPadding(dp(10), dp(16), dp(10), dp(4));
    return v;
  }

  private View sidebarItem(int icon, String text, Runnable action) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(44));
    row.setPadding(dp(10), 0, dp(10), 0);
    row.setBackground(ripple(Color.TRANSPARENT, 10));
    ImageView glyph = new ImageView(this);
    glyph.setImageDrawable(icon(icon, accent, 20));
    row.addView(glyph, new LinearLayout.LayoutParams(dp(20), dp(20)));
    TextView caption = label(text, 15, ink);
    caption.setPadding(dp(12), 0, 0, 0);
    row.addView(caption);
    row.setFocusable(true);
    row.setClickable(true);
    row.setContentDescription(text);
    Motion.press(row, 0.97f);
    row.setOnClickListener(
        v -> {
          if (!busy) action.run();
        });
    return row;
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

  private Drawable icon(int res, int color, int size) {
    Drawable d = getDrawable(res).mutate();
    d.setTint(color);
    d.setBounds(0, 0, dp(size), dp(size));
    return d;
  }

  private void tintIcons(TextView view, int color) {
    for (Drawable d : view.getCompoundDrawablesRelative()) if (d != null) d.setTint(color);
  }

  private Drawable ripple(int color, int radius) {
    GradientDrawable mask = round(Color.WHITE, radius);
    return new RippleDrawable(
        ColorStateList.valueOf(alpha(accent, dark ? 0x40 : 0x26)),
        color == Color.TRANSPARENT ? null : round(color, radius),
        mask);
  }

  /** Secondary toolbar button: soft grey pill, ink label, accent glyph. */
  private Button button(String text, Runnable action) {
    return button(0, text, action);
  }

  private Button button(int iconRes, String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(ink);
    b.setMinWidth(dp(48));
    b.setMinimumWidth(dp(48));
    b.setMinHeight(dp(48));
    b.setMinimumHeight(dp(48));
    boolean iconOnly = text.isEmpty();
    b.setPadding(
        dp(iconRes != 0 ? (iconOnly ? 14 : 12) : 16), dp(8), dp(iconOnly ? 14 : 16), dp(8));
    if (iconRes != 0) {
      b.setCompoundDrawablesRelative(icon(iconRes, accent, 18), null, null, null);
      b.setCompoundDrawablePadding(iconOnly ? 0 : dp(6));
    }
    b.setBackground(ripple(fill, 12));
    b.setStateListAnimator(null);
    Motion.press(b, 0.95f);
    b.setOnClickListener(
        v -> {
          if (!busy) action.run();
        });
    return b;
  }

  /** Primary action: filled with the accent colour. */
  private Button primary(int iconRes, String text, Runnable action) {
    Button b = button(iconRes, text, action);
    b.setTextColor(Color.WHITE);
    b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    b.setBackground(ripple(accent, 12));
    b.setElevation(dp(2));
    tintIcons(b, Color.WHITE);
    return b;
  }

  /** Dock item: icon above a short label, bounces when chosen. */
  private Button dockButton(int iconRes, String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(10.5f);
    b.setAllCaps(false);
    b.setTextColor(ink);
    b.setMaxLines(1);
    b.setEllipsize(android.text.TextUtils.TruncateAt.END);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(54));
    b.setMinimumHeight(dp(54));
    b.setPadding(dp(2), dp(7), dp(2), dp(4));
    b.setCompoundDrawablesRelative(null, icon(iconRes, accent, 22), null, null);
    b.setCompoundDrawablePadding(dp(3));
    b.setBackground(ripple(Color.TRANSPARENT, 16));
    b.setStateListAnimator(null);
    Motion.press(b, 0.9f);
    b.setOnClickListener(
        v -> {
          if (!busy) {
            Motion.bounce(v);
            action.run();
          }
        });
    return b;
  }

  private GradientDrawable round(int color, int radius) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(color);
    bg.setCornerRadius(dp(radius));
    return bg;
  }

  /** White sheet with a hairline edge; casts a soft shadow in light mode. */
  private Drawable sheet(int radius) {
    GradientDrawable shape = round(surface, radius);
    shape.setStroke(1, alpha(border, dark ? 0xFF : 0x99));
    return shape;
  }

  private void lift(View view, int elevation) {
    view.setElevation(dp(dark ? 0 : elevation));
    if (Build.VERSION.SDK_INT >= 28) {
      view.setOutlineAmbientShadowColor(alpha(Color.BLACK, 0x30));
      view.setOutlineSpotShadowColor(alpha(Color.BLACK, 0x40));
    }
  }

  /** Rounded colour tile used as a document-type icon (HWP / XLS / PDF). */
  private TextView tile(String text, int color, int size) {
    TextView v = label(text, size >= 44 ? 12 : 10, Color.WHITE);
    v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    v.setGravity(Gravity.CENTER);
    v.setPadding(0, 0, 0, 0);
    GradientDrawable bg =
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {blend(color, Color.WHITE, 0.22f), color});
    bg.setCornerRadius(dp(size * 0.27f));
    v.setBackground(bg);
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return v;
  }

  private TextView pill(String text) {
    TextView v = label(text, 12, accent);
    v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    v.setPadding(dp(10), dp(5), dp(10), dp(5));
    v.setBackground(round(alpha(accent, dark ? 0x33 : 0x1A), 10));
    return v;
  }

  /** A position pill and a short hint, wrapping on narrow screens. */
  private View infoRow(String position, String hint) {
    LinearLayout row = horizontal();
    row.setPadding(0, dp(10), 0, dp(4));
    row.addView(pill(position));
    TextView text = label(hint, 12, muted);
    text.setPadding(dp(2), dp(5), 0, dp(5));
    row.addView(text);
    return row;
  }

  private TextView sectionTitle(String text) {
    TextView v = label(text, 13, muted);
    v.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    v.setPadding(dp(4), dp(10), 0, dp(8));
    return v;
  }

  private void gradientText(TextView view, int from, int to) {
    view.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          android.text.Layout layout = view.getLayout();
          float width = 1;
          if (layout != null)
            for (int i = 0; i < layout.getLineCount(); i++)
              width = Math.max(width, layout.getLineWidth(i));
          view.getPaint()
              .setShader(new LinearGradient(0, 0, width, 0, from, to, Shader.TileMode.CLAMP));
        });
  }

  private LinearLayout card(String tag, String ext, String title, String detail, Runnable action) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.HORIZONTAL);
    c.setGravity(Gravity.CENTER_VERTICAL);
    c.setPadding(dp(16), dp(16), dp(14), dp(16));
    c.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(alpha(accent, dark ? 0x40 : 0x1F)), sheet(18), null));
    lift(c, 2);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(12);
    c.setLayoutParams(p);
    c.addView(
        tile(ext.toUpperCase(Locale.ROOT), typeColor(ext), 48),
        new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout text = vertical();
    text.setPadding(dp(14), 0, dp(8), 0);
    c.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    TextView badge = label(tag, 11, muted);
    badge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    badge.setPadding(0, 0, 0, dp(2));
    text.addView(badge);
    TextView heading = label(title, 18, ink);
    heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    heading.setPadding(0, 0, 0, dp(2));
    heading.setMaxLines(2);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    text.addView(heading);
    TextView body = label(detail, 14, muted);
    body.setPadding(0, 0, 0, 0);
    body.setLineSpacing(0, 1.2f);
    text.addView(body);
    if (action != null) {
      ImageView chevron = new ImageView(this);
      chevron.setImageDrawable(icon(R.drawable.ic_chevron, muted, 18));
      chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      c.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
      c.setOnClickListener(
          v -> {
            if (!busy) action.run();
          });
      c.setFocusable(true);
      c.setClickable(true);
      c.setContentDescription(title + ". " + detail);
      Motion.press(c, 0.98f);
    }
    return c;
  }

  private void updateHeader() {
    if (typePill == null) return;
    if (input == null) {
      typePill.setVisibility(View.GONE);
      return;
    }
    typePill.setText(extension.toUpperCase(Locale.ROOT));
    typePill.setBackground(round(typeColor(extension), 8));
    typePill.setVisibility(View.VISIBLE);
  }

  /** New screens settle in with a stagger; refreshed screens just pulse the part that changed. */
  private void transition(String key, View changed) {
    boolean settling = pendingScroll >= 0;
    if (key.equals(lastScreen)) {
      if (!settling && changed != null) Motion.refresh(changed);
    } else Motion.stagger(content, dp(14));
    lastScreen = key;
  }

  /** Page turns slide the page in from the side of travel. */
  private void turnPage(String key, View page) {
    if (turn != 0 && key.equals(lastScreen)) Motion.slide(page, turn, dp(56));
    else transition(key, page);
    lastScreen = key;
    turn = 0;
  }

  private void restoreScroll() {
    if (pendingScroll < 0) return;
    int y = pendingScroll;
    pendingScroll = -1;
    scroll.post(() -> scroll.scrollTo(0, y));
  }

  private void home() {
    content.removeAllViews();
    pendingScroll = -1;
    updateHeader();
    TextView hero = label("문서 작업을\n가볍게", wide ? 42 : 34, ink);
    hero.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    hero.setLineSpacing(0, 1.05f);
    hero.setPadding(dp(2), dp(18), 0, dp(8));
    gradientText(hero, accent, Color.parseColor(dark ? "#BF5AF2" : "#AF52DE"));
    content.addView(hero);
    TextView lead = label("한글부터 엑셀까지, 한 곳에서.", 16, muted);
    lead.setPadding(dp(2), 0, 0, 0);
    content.addView(lead);
    LinearLayout quick = horizontal();
    quick.setPadding(0, dp(18), 0, dp(18));
    quick.addView(primary(R.drawable.ic_open, "문서 열기", () -> guard(() -> picker(OPEN, "*/*"))));
    quick.addView(button(R.drawable.ic_add, "새 문서", this::newDocumentMenu));
    quick.addView(button(R.drawable.ic_appearance, "모양", this::appearance));
    content.addView(quick);
    if (history != null && history.available()) {
      content.addView(sectionTitle("이어 하기"));
      content.addView(
          card(
              "자동 저장됨",
              history.extension(),
              history.name(),
              "마지막 작업을 그대로 이어서 열어요",
              this::restore));
    }
    content.addView(sectionTitle("무엇을 할까요?"));
    View[] cards = {
      card("HWP · HWPX", "hwp", "한글 문서", "문단과 서식을 고치고 PDF로 내보내세요", () -> picker(OPEN, "*/*")),
      card("XLSX · XLS", "xlsx", "엑셀 작업", "함수 계산, 범위 붙여넣기, 셀 서식을 한 번에", () -> picker(OPEN, "*/*")),
      card("PDF", "pdf", "읽기부터 서명까지", "확대해서 읽고, 필기하고, 페이지를 정리하세요", () -> picker(OPEN, "application/pdf"))
    };
    if (wide) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      for (int i = 0; i < cards.length; i++) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        if (i < cards.length - 1) p.setMarginEnd(dp(12));
        p.bottomMargin = dp(12);
        row.addView(cards[i], p);
      }
      content.addView(row);
    } else for (View card : cards) content.addView(card);
    TextView footer = label("편집은 자동 저장 · 원본은 별도 보관\n문서는 기기 안에서만 처리됩니다.", 13, muted);
    footer.setPadding(dp(4), dp(10), 0, dp(6));
    footer.setLineSpacing(0, 1.25f);
    content.addView(footer);
    transition("home", null);
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
    LinearLayout form = form();
    EditText search = field("함수 이름 검색", "");
    addField(form, search);
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
    int[] values = {0xff007aff, 0xff1d1d1f, 0xffff3b30, 0xff34c759};
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
    int y = scroll == null ? 0 : scroll.getScrollY();
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
        () -> {
          pendingScroll = y;
          redraw.run();
        },
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
    updateHeader();
    if (pendingScroll < 0) scroll.scrollTo(0, 0);
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
    LinearLayout fields = form();
    EditText find = field("찾을 내용", ""), replacement = field("바꿀 내용", "");
    addField(fields, find);
    addField(fields, replacement);
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
    updateHeader();
    LinearLayout nav = horizontal();
    content.addView(nav);
    nav.addView(button(R.drawable.ic_back, "이전", () -> hangulPage(-1)));
    nav.addView(button(R.drawable.ic_forward, "다음", () -> hangulPage(1)));
    nav.addView(button(R.drawable.ic_search, "찾기", this::searchHangul));
    nav.addView(button(R.drawable.ic_layout, "레이아웃", this::preview));
    int end = Math.min(blockStart + 40, hangul.blocks.size());
    content.addView(
        infoRow(
            (blockStart + 1) + "–" + end + " / " + hangul.blocks.size(),
            "문단을 누르면 바로 고쳐요 · 길게 누르면 서식·추가·삭제"));
    // The document reads like a page: one white sheet, comfortable line height, no clutter.
    LinearLayout paper = vertical();
    paper.setBackground(sheet(14));
    lift(paper, 3);
    paper.setPadding(dp(wide ? 36 : 10), dp(wide ? 32 : 14), dp(wide ? 36 : 10), dp(wide ? 32 : 14));
    LinearLayout.LayoutParams paperParams = new LinearLayout.LayoutParams(readingWidth, -2);
    paperParams.gravity = Gravity.CENTER_HORIZONTAL;
    paperParams.topMargin = dp(6);
    paperParams.bottomMargin = dp(10);
    content.addView(paper, paperParams);
    View highlighted = null;
    for (int index = blockStart; index < end; index++) {
      TextBlock block = hangul.blocks.get(index);
      final int position = index;
      LinearLayout row = vertical();
      row.setPadding(dp(10), dp(7), dp(10), dp(7));
      row.setBackground(ripple(Color.TRANSPARENT, 10));
      boolean located = !block.label.startsWith("본문");
      if (located || !block.editable) {
        TextView where = label(block.label + (block.editable ? "" : " · 읽기 전용"), 11, muted);
        where.setPadding(0, 0, 0, dp(2));
        row.addView(where);
      }
      TextView text = label(block.text.isEmpty() ? "빈 문단" : block.text, 17, ink);
      text.setPadding(0, 0, 0, 0);
      text.setLineSpacing(0, 1.42f);
      try {
        DocumentStyle style = hangul.style(block);
        text.setTextSize(Math.max(15, Math.min(34, style.size + 3)));
        text.setTypeface(Typeface.create(style.font, style.bold ? Typeface.BOLD : Typeface.NORMAL));
        text.setGravity(
            style.alignment.equals("center")
                ? Gravity.CENTER_HORIZONTAL
                : style.alignment.equals("right") ? Gravity.END : Gravity.START);
      } catch (Exception ignored) {
      }
      if (block.text.isEmpty()) {
        text.setTextSize(13);
        text.setTextColor(alpha(muted, 0x99));
      } else if (!block.editable) text.setTextColor(blend(ink, muted, 0.35f));
      row.addView(text);
      row.setClickable(true);
      row.setFocusable(true);
      row.setContentDescription(
          (block.text.isEmpty() ? "빈 문단" : block.text)
              + (block.editable ? ". 눌러서 고치기" : ". 읽기 전용"));
      Motion.press(row, 0.99f);
      row.setOnClickListener(
          v -> {
            if (busy) return;
            if (!block.editable) message("이 부분은 읽기 전용이라 고칠 수 없어요.");
            else quickEdit(block, position);
          });
      row.setOnLongClickListener(
          v -> {
            if (!busy && block.editable) paragraphMenu(block, position);
            return true;
          });
      paper.addView(row);
      if (position == flashBlock) highlighted = row;
    }
    LinearLayout footer = horizontal();
    footer.setPadding(0, dp(4), 0, 0);
    if (blockStart > 0) footer.addView(button(R.drawable.ic_back, "앞 문단들", () -> hangulPage(-1)));
    if (end < hangul.blocks.size())
      footer.addView(button(R.drawable.ic_forward, "다음 문단들", () -> hangulPage(1)));
    content.addView(footer);
    if (highlighted != null) {
      View row = highlighted;
      Motion.flash(row, alpha(accent, dark ? 0x55 : 0x33), dp(10));
      if (revealFlash)
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, paper.getTop() + row.getTop() - dp(96))));
    }
    flashBlock = -1;
    revealFlash = false;
    turnPage("hangul", paper);
    restoreScroll();
  }

  private void hangulPage(int direction) {
    int next = Math.max(0, Math.min(blockStart + direction * 40, hangul.blocks.size() - 1));
    next -= next % 40;
    if (next == blockStart) return;
    blockStart = next;
    turn = direction;
    scroll.scrollTo(0, 0);
    showHangul();
  }

  private void quickEdit(TextBlock block, int position) {
    edit(
        "문단 고치기",
        block.text,
        false,
        value ->
            mutate(
                "수정 중…",
                () -> block.set(value),
                () -> {
                  flashBlock = position;
                  show();
                }),
        "서식·추가·삭제",
        () -> paragraphMenu(block, position));
  }

  private void paragraphMenu(TextBlock block, int position) {
    menu(
        "문단 편집",
        new String[] {"텍스트 수정", "글꼴·크기·굵게·정렬", "아래에 문단 추가", "문단 삭제"},
        choice -> {
          if (choice == 0) quickEdit(block, position);
          else if (choice == 1) {
            try {
              styleDialog(
                  hangul.style(block),
                  false,
                  style ->
                      mutate(
                          "서식 적용 중…",
                          () -> hangul.format(block, style),
                          () -> {
                            flashBlock = position;
                            show();
                          }));
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
                        "문단 추가 중…",
                        () -> hangul.insertAfter(block, value),
                        () -> {
                          flashBlock = position + 1;
                          show();
                        }));
          else
            new AlertDialog.Builder(this)
                .setTitle("문단 삭제")
                .setMessage("이 문단을 지웁니다. 되돌리기로 다시 살릴 수 있어요.")
                .setNegativeButton("취소", null)
                .setPositiveButton(
                    "삭제",
                    (d, w) -> mutate("문단 삭제 중…", () -> hangul.deleteParagraph(block), this::show))
                .show();
        });
  }

  private void searchHangul() {
    edit(
        "문서에서 찾기",
        "",
        false,
        query -> {
          if (query.isEmpty()) return;
          int from = flashSearch < 0 ? blockStart - 1 : flashSearch;
          for (int offset = 1; offset <= hangul.blocks.size(); offset++) {
            int i = (from + offset + hangul.blocks.size()) % hangul.blocks.size();
            if (hangul.blocks.get(i).text.contains(query)) {
              int pageStart = i - i % 40;
              turn = pageStart == blockStart ? 0 : pageStart > blockStart ? 1 : -1;
              blockStart = pageStart;
              flashBlock = i;
              flashSearch = i;
              revealFlash = true;
              scroll.scrollTo(0, 0);
              showHangul();
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
                updateHeader();
                LinearLayout nav = horizontal();
                content.addView(nav);
                nav.addView(button(R.drawable.ic_text, "글로 보기", this::showHangul));
                nav.addView(
                    button(
                        R.drawable.ic_back,
                        "이전",
                        () -> {
                          if (layoutPage > 0) {
                            layoutPage--;
                            turn = -1;
                            showLayout();
                          }
                        }));
                nav.addView(
                    button(
                        R.drawable.ic_forward,
                        "다음",
                        () -> {
                          if (layoutPage + 1 < layoutPdf.count()) {
                            layoutPage++;
                            turn = 1;
                            showLayout();
                          }
                        }));
                nav.addView(primary(R.drawable.ic_save, "PDF 저장", () -> exportPicker(EXPORT_PDF)));
                content.addView(
                    infoRow((layoutPage + 1) + " / " + layoutPdf.count() + " 페이지", "레이아웃 미리보기"));
                TextView notice = label(layoutNotice, 12, ink);
                notice.setLineSpacing(0, 1.25f);
                notice.setPadding(dp(12), dp(10), dp(12), dp(10));
                notice.setBackground(
                    round(alpha(Color.parseColor(dark ? "#FFD60A" : "#FF9F0A"), dark ? 0x26 : 0x1F), 10));
                LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(-1, -2);
                noticeParams.bottomMargin = dp(6);
                content.addView(notice, noticeParams);
                ImageView image = new ImageView(this);
                image.setAdjustViewBounds(true);
                image.setImageBitmap(next);
                turnPage("layout", addPage(image));
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
    updateHeader();
    // Sheet tabs as a segmented control, like Numbers.
    HorizontalScrollView tabScroll = new HorizontalScrollView(this);
    tabScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout tabs = new LinearLayout(this);
    tabs.setOrientation(LinearLayout.HORIZONTAL);
    tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
    tabs.setBackground(round(fill, 13));
    tabScroll.addView(tabs);
    for (int i = 0; i < excel.names.size(); i++) {
      final int index = i;
      Button tab =
          button(
              excel.names.get(i),
              () -> {
                if (sheet != index) {
                  sheet = index;
                  firstRow = firstCol = 0;
                  showExcel();
                }
              });
      tab.setMinHeight(dp(44));
      tab.setMinimumHeight(dp(44));
      if (i == sheet) {
        tab.setBackground(ripple(surface, 10));
        tab.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        tab.setElevation(dp(1));
      } else {
        tab.setBackground(ripple(Color.TRANSPARENT, 10));
        tab.setTextColor(muted);
      }
      tab.setContentDescription(excel.names.get(i) + (i == sheet ? ", 선택됨" : ""));
      tabs.addView(tab);
    }
    LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(-2, -2);
    tabParams.bottomMargin = dp(8);
    content.addView(tabScroll, tabParams);
    if (sheet != lastSheet) lastScreen = "";
    lastSheet = sheet;

    LinearLayout nav = horizontal();
    content.addView(nav);
    nav.addView(
        button(
            R.drawable.ic_back,
            "앞 열",
            () -> {
              if (firstCol == 0) return;
              firstCol = Math.max(0, firstCol - 5);
              showExcel();
            }));
    nav.addView(
        button(
            R.drawable.ic_forward,
            "뒤 열",
            () -> {
              firstCol = Math.min(extension.equals("xls") ? 251 : 16379, firstCol + 5);
              showExcel();
            }));
    nav.addView(
        button(
            R.drawable.ic_up,
            "위로",
            () -> {
              if (firstRow == 0) return;
              firstRow = Math.max(0, firstRow - 20);
              showExcel();
            }));
    nav.addView(
        button(
            R.drawable.ic_down,
            "아래로",
            () -> {
              firstRow = Math.min(extension.equals("xls") ? 65516 : 1048556, firstRow + 20);
              showExcel();
            }));
    nav.addView(
        button(
            R.drawable.ic_target,
            "셀 이동",
            () -> edit("셀 주소", "A1", false, address -> jump(address))));
    nav.addView(button(R.drawable.ic_search, "찾기", this::searchSheet));
    content.addView(
        infoRow(
            Spreadsheet.address(firstCol, firstRow)
                + " : "
                + Spreadsheet.address(firstCol + 4, firstRow + Math.max(0, rows.size() - 1)),
            "셀을 누르면 값·수식을, 길게 누르면 서식을 고쳐요"));

    LinearLayout frame = vertical();
    frame.setBackground(sheet(14));
    frame.setClipToOutline(true);
    lift(frame, 2);
    HorizontalScrollView area = new HorizontalScrollView(this);
    TableLayout table = new TableLayout(this);
    area.addView(table);
    frame.addView(area);
    LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(-1, -2);
    frameParams.topMargin = dp(6);
    content.addView(frame, frameParams);
    int cellWidth = dp(wide ? 150 : 120), gutter = dp(46);
    int headerFill = dark ? Color.parseColor("#2C2C30") : Color.parseColor("#F2F2F6");
    int zebra = dark ? Color.parseColor("#28282B") : Color.parseColor("#FAFAFC");
    int grid = alpha(border, dark ? 0xFF : 0xB3);
    TableRow headings = new TableRow(this);
    table.addView(headings);
    TextView corner = label("", 12, muted);
    corner.setWidth(gutter);
    corner.setBackground(cellBackground(headerFill, grid, false));
    headings.addView(corner);
    for (int col = 0; col < 5; col++) {
      TextView heading =
          label(Spreadsheet.address(firstCol + col, 0).replaceAll("[0-9]", ""), 12, muted);
      heading.setWidth(cellWidth);
      heading.setGravity(Gravity.CENTER);
      heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
      heading.setPadding(0, dp(9), 0, dp(9));
      heading.setBackground(cellBackground(headerFill, grid, false));
      headings.addView(heading);
    }
    int errorColor = Color.parseColor(dark ? "#FF6961" : "#D70015");
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      TableRow row = new TableRow(this);
      table.addView(row);
      TextView number = label("" + (firstRow + rowIndex + 1), 12, muted);
      number.setWidth(gutter);
      number.setGravity(Gravity.CENTER);
      number.setBackground(cellBackground(headerFill, grid, false));
      row.addView(number);
      int rowFill = rowIndex % 2 == 1 ? zebra : surface;
      for (int colIndex = 0; colIndex < 5; colIndex++) {
        if (rows.get(rowIndex)[colIndex] == null) continue;
        String address = Spreadsheet.address(firstCol + colIndex, firstRow + rowIndex);
        DocumentStyle style = styles.get(rowIndex)[colIndex];
        String shown = rows.get(rowIndex)[colIndex];
        TextView cell = label(shown, Math.max(12, Math.min(24, Math.round(style.size + 2))), ink);
        if (shown.startsWith("#") && (shown.endsWith("!") || shown.endsWith("?") || shown.contains("(")))
          cell.setTextColor(errorColor);
        cell.setWidth(cellWidth);
        cell.setMinHeight(dp(48));
        cell.setPadding(dp(10), dp(10), dp(10), dp(10));
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
        cell.setBackground(cellBackground(rowFill, grid, true));
        row.addView(cell);
        cell.setContentDescription(address + " " + shown);
        cell.setOnClickListener(
            v -> {
              if (!busy) editCell(address, style);
            });
        cell.setOnLongClickListener(
            v -> {
              if (!busy) styleCell(address, style);
              return true;
            });
        if (address.equals(flashCell)) Motion.flash(cell, alpha(accent, dark ? 0x66 : 0x40), 0);
      }
    }
    flashCell = null;
    transition("sheet", frame);
    restoreScroll();
  }

  /** Hairline grid: only the right and bottom edges, so lines never double up. */
  private Drawable cellBackground(int color, int line, boolean touchable) {
    GradientDrawable edge = new GradientDrawable();
    edge.setColor(line);
    GradientDrawable body = new GradientDrawable();
    body.setColor(color);
    LayerDrawable layers = new LayerDrawable(new Drawable[] {edge, body});
    layers.setLayerInset(1, 0, 0, 1, 1);
    return touchable
        ? new RippleDrawable(
            ColorStateList.valueOf(alpha(accent, dark ? 0x40 : 0x2A)), layers, null)
        : layers;
  }

  private void editCell(String address, DocumentStyle style) {
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
                              () -> {
                                flashCell = address;
                                show();
                              }),
                      "서식",
                      () -> styleCell(address, style));
              });
        },
        () -> {});
  }

  private void styleCell(String address, DocumentStyle style) {
    styleDialog(
        style,
        true,
        format ->
            mutate(
                "셀 서식 적용 중…",
                () -> excel.format(sheet, address, format),
                () -> {
                  flashCell = address;
                  show();
                }));
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
    updateHeader();
    LinearLayout nav = horizontal();
    content.addView(nav);
    nav.addView(
        button(
            R.drawable.ic_back,
            "이전",
            () -> {
              if (page > 0) {
                page--;
                turn = -1;
                showPdf();
              }
            }));
    nav.addView(
        button(
            R.drawable.ic_forward,
            "다음",
            () -> {
              if (page + 1 < pdf.count()) {
                page++;
                turn = 1;
                showPdf();
              }
            }));
    nav.addView(
        button(
            R.drawable.ic_pages,
            "페이지 이동",
            () ->
                edit(
                    "페이지 번호",
                    "" + (page + 1),
                    false,
                    value -> {
                      try {
                        int n = Integer.parseInt(value.trim());
                        if (n < 1 || n > pdf.count()) throw new IOException("페이지 범위를 벗어났습니다.");
                        turn = Integer.compare(n - 1, page);
                        page = n - 1;
                        showPdf();
                      } catch (Exception e) {
                        error(e);
                      }
                    })));
    LinearLayout tools = horizontal();
    tools.setPadding(0, dp(6), 0, 0);
    content.addView(tools);
    Button pen = button(R.drawable.ic_pen, "펜", () -> {});
    tools.addView(pen);
    pen.setOnClickListener(
        v -> {
          if (!busy) {
            pageCanvas.ink = !pageCanvas.ink;
            inkMode = pageCanvas.ink;
            pageCanvas.pendingText = null;
            pageCanvas.pendingImage = null;
            penLook(pen, pageCanvas.ink);
            message(pageCanvas.ink ? "펜을 켰어요. 페이지에 바로 쓰세요." : "펜을 껐어요. 화면을 움직일 수 있어요.");
          }
        });
    tools.addView(
        button(
            R.drawable.ic_text,
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
                        penLook(pen, false);
                        message("넣을 위치를 누르세요.");
                      }
                    })));
    tools.addView(button(R.drawable.ic_sign, "서명", () -> picker(SIGNATURE, "image/*")));
    tools.addView(button(R.drawable.ic_tools, "펜 설정", this::penSettings));
    content.addView(
        infoRow(
            (page + 1) + " / " + pdf.count() + " 페이지",
            "쓰거나 넣을 때마다 자동 저장 · 펜을 끄면 화면을 움직일 수 있어요"));
    pageCanvas =
        new PageCanvas(
            this,
            pageBitmap,
            marks.computeIfAbsent(page, key -> new ArrayList<>()),
            () -> mutate("필기 자동저장 중…", () -> {}, this::show));
    pageCanvas.ink = inkMode;
    pageCanvas.strokeColor = penColor;
    pageCanvas.strokeWidth = penWidth;
    penLook(pen, inkMode);
    turnPage("pdf", addPage(pageCanvas));
    restoreScroll();
  }

  private void penLook(Button pen, boolean on) {
    pen.setText(on ? "펜 끄기" : "펜");
    pen.setTextColor(on ? Color.WHITE : ink);
    pen.setBackground(ripple(on ? accent : fill, 12));
    tintIcons(pen, on ? Color.WHITE : accent);
    pen.setContentDescription(on ? "펜 끄기" : "펜 켜기");
  }

  private ZoomPage addPage(View view) {
    ZoomPage area = new ZoomPage(this, view, zoom, value -> zoom = value);
    area.setPadding(0, dp(4), 0, dp(14));
    view.setOutlineProvider(ViewOutlineProvider.BOUNDS);
    view.setElevation(dp(dark ? 2 : 5));
    LinearLayout bar = horizontal();
    bar.setPadding(0, dp(8), 0, dp(4));
    TextView amount = pill(Math.round(zoom * 100) + "%");
    Button out =
        button(
            R.drawable.ic_zoom_out,
            "",
            () -> {
              area.setZoom(zoom - .25f);
              amount.setText(Math.round(zoom * 100) + "%");
            });
    out.setContentDescription("축소");
    Button zoomIn =
        button(
            R.drawable.ic_zoom_in,
            "",
            () -> {
              area.setZoom(zoom + .25f);
              amount.setText(Math.round(zoom * 100) + "%");
            });
    zoomIn.setContentDescription("확대");
    bar.addView(out);
    bar.addView(zoomIn);
    bar.addView(
        button(
            R.drawable.ic_fit,
            "맞춤",
            () -> {
              area.setZoom(1);
              amount.setText("100%");
            }));
    LinearLayout readout = new LinearLayout(this);
    readout.setGravity(Gravity.CENTER_VERTICAL);
    readout.setMinimumHeight(dp(48));
    readout.addView(amount);
    TextView hint = label("두 손가락으로 확대", 12, muted);
    hint.setPadding(dp(8), 0, 0, 0);
    readout.addView(hint);
    bar.addView(readout);
    content.addView(bar);
    content.addView(area, new LinearLayout.LayoutParams(-1, -2));
    return area;
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
    LinearLayout form = form();
    EditText font = field("글꼴 이름", initial.font), size = field("크기(pt)", "" + initial.size);
    size.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    addField(form, font);
    addField(form, size);
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
    v.setTextSize(16);
    v.setTextColor(ink);
    v.setHintTextColor(muted);
    v.setPadding(dp(14), dp(12), dp(14), dp(12));
    GradientDrawable focused = round(well, 12), idle = round(well, 12);
    focused.setStroke(dp(2), accent);
    idle.setStroke(1, border);
    StateListDrawable bg = new StateListDrawable();
    bg.addState(new int[] {android.R.attr.state_focused}, focused);
    bg.addState(new int[] {}, idle);
    v.setBackground(bg);
    return v;
  }

  private LinearLayout form() {
    LinearLayout form = vertical();
    form.setPadding(dp(20), dp(10), dp(20), dp(4));
    return form;
  }

  private void addField(LinearLayout form, View view) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(10);
    form.addView(view, p);
  }

  private void edit(String title, String value, boolean multiline, TextAction action) {
    edit(title, value, multiline, action, null, null);
  }

  /**
   * Text entry sheet. Long text wraps instead of scrolling sideways, the keyboard opens at once
   * and the Done key applies. An optional middle button leads to related actions.
   */
  private void edit(
      String title,
      String value,
      boolean multiline,
      TextAction action,
      String extra,
      Runnable extraAction) {
    EditText v = field(title, value);
    if (multiline) {
      v.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
      v.setSingleLine(false);
      v.setMaxLines(8);
    } else {
      v.setInputType(InputType.TYPE_CLASS_TEXT);
      v.setHorizontallyScrolling(false);
      v.setMaxLines(7);
      v.setImeOptions(EditorInfo.IME_ACTION_DONE);
    }
    v.setLineSpacing(0, 1.2f);
    v.setSelection(v.getText().length());
    LinearLayout box = form();
    addField(box, v);
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton(
                "적용",
                (d, w) -> {
                  if (!busy) action.accept(v.getText().toString());
                });
    if (extra != null && extraAction != null)
      builder.setNeutralButton(
          extra,
          (d, w) -> {
            if (!busy) extraAction.run();
          });
    AlertDialog dialog = builder.create();
    if (dialog.getWindow() != null)
      dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    v.setOnEditorActionListener(
        (view, id, event) -> {
          if (id == EditorInfo.IME_ACTION_DONE) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
          }
          return false;
        });
    dialog.show();
    v.requestFocus();
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
      for (Button b : new Button[] {undoButton, redoButton, saveButton})
        b.animate().alpha(b.isEnabled() ? 1f : 0.35f).setDuration(180).start();
    }
  }

  private void enable(View view, boolean value) {
    view.setEnabled(value);
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), value);
    }
  }

  private final Runnable hideHud =
      () -> {
        TextView banner = hud;
        if (banner == null) return;
        banner
            .animate()
            .alpha(0)
            .translationY(-dp(16))
            .setDuration(220)
            .setInterpolator(Motion.EASE)
            .withEndAction(() -> banner.setVisibility(View.GONE))
            .start();
      };

  private void message(String text) {
    if (hud == null) {
      Toast.makeText(this, text, Toast.LENGTH_LONG).show();
      return;
    }
    hud.removeCallbacks(hideHud);
    hud.setText(text);
    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) hud.getLayoutParams();
    p.topMargin = topInset + dp(12);
    hud.setLayoutParams(p);
    hud.setVisibility(View.VISIBLE);
    hud.animate().cancel();
    hud.setAlpha(0);
    hud.setTranslationY(-dp(24));
    hud.animate().alpha(1).translationY(0).setDuration(320).setInterpolator(Motion.EASE).start();
    hud.postDelayed(hideHud, 2600 + Math.min(3000, text.length() * 40L));
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
