package com.homesweetlove.hwpapp;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.homesweetlove.hwpapp.core.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int OPEN = 1, SAVE = 2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root, content, actions;
    private TextView title, subtitle, status;
    private ScrollView scroll;
    private String name = "", extension = "";
    private File input;
    private HangulDocument hangul;
    private Spreadsheet excel;
    private PdfDocument pdf;
    private Bitmap pageBitmap;
    private PageCanvas pageCanvas;
    private final Map<Integer, List<PageCanvas.Mark>> marks = new HashMap<>();
    private int page = 0, blockStart = 0, sheet = 0, firstRow = 0, firstCol = 0;
    private boolean busy, dirty, destroyed;
    private long revision = 0;
    private final int ink = Color.rgb(30, 38, 34), green = Color.rgb(48, 92, 75);
    private interface Job { void run() throws Exception; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = vertical(); root.setBackgroundColor(Color.rgb(245, 244, 240));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(dp(18), insets.getSystemWindowInsetTop() + dp(12), dp(18), insets.getSystemWindowInsetBottom() + dp(12)); return insets;
        });
        root.requestApplyInsets();
        title = label("HWP Desk", 28, ink); title.setTypeface(null, Typeface.BOLD); root.addView(title);
        subtitle = label("내 문서, 내 휴대폰에서", 14, green); root.addView(subtitle);
        actions = horizontal(); root.addView(actions);
        actions.addView(button("파일 열기", () -> discardGuard(this::picker)));
        actions.addView(button("수정본 저장", this::savePicker));
        status = label("HWP · HWPX · PDF · XLSX · XLS", 12, green); status.setPadding(0, dp(10), 0, dp(10)); root.addView(status);
        scroll = new ScrollView(this); content = vertical(); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        home();
        if (getIntent().getData() != null) open(getIntent().getData());
    }
    private LinearLayout vertical() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout horizontal() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setPadding(0, dp(6), 0, dp(6)); return text;
    }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(green); button.setTextSize(13);
        button.setOnClickListener(v -> { if (!busy) action.run(); }); return button;
    }
    private void home() {
        content.removeAllViews();
        content.addView(label("문서를 가볍게 열고\n필요한 부분을 바로 수정하세요", 25, ink));
        content.addView(label("한글 문서\n문단·표 텍스트 확인과 수정\n원래 HWP / HWPX 형식으로 저장", 16, ink));
        content.addView(label("PDF\n페이지 보기, 글자 추가, 펜으로 필기\n기존 본문 위에 추가 내용을 저장", 16, ink));
        content.addView(label("엑셀\n여러 시트의 셀과 수식 확인·수정\n셀 주소로 바로 이동", 16, ink));
        content.addView(label("파일은 기기 안에서 처리합니다.\n한글·엑셀은 내용 중심 보기입니다.\n저장 버튼으로 수정본을 새 파일로 내보냅니다.", 13, green));
        String recent = getPreferences(MODE_PRIVATE).getString("recent", "");
        if (!recent.isEmpty()) content.addView(button("최근 문서 열기", () -> open(Uri.parse(recent))));
    }
    private void picker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, OPEN);
    }
    private void savePicker() {
        if (input == null) { message("먼저 파일을 열어 주세요."); return; }
        if (pageCanvas != null && pageCanvas.current != null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime());
        intent.putExtra(Intent.EXTRA_TITLE, name.replaceFirst("(?i)\\." + extension + "$", "") + "_수정본." + extension);
        startActivityForResult(intent, SAVE);
    }
    private String mime() {
        switch (extension) {
            case "pdf": return "application/pdf";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls": return "application/vnd.ms-excel";
            case "hwpx": return "application/vnd.hancom.hwpx";
            default: return "application/x-hwp";
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN) {
            try { if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            open(uri);
        } else if (request == SAVE) save(uri);
    }
    private void open(Uri uri) {
        job("문서를 여는 중…", () -> {
            String display = null;
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) display = cursor.getString(0);
            }
            if (display == null) display = "문서";
            String ext = display.contains(".") ? display.substring(display.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!Arrays.asList("hwp", "hwpx", "pdf", "xlsx", "xls").contains(ext)) throw new IOException("HWP, HWPX, PDF, XLSX, XLS 파일을 선택해 주세요.");
            File local = File.createTempFile("document-", "." + ext, getCacheDir());
            HangulDocument newHangul = null; Spreadsheet newExcel = null; PdfDocument newPdf = null;
            try {
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(local)) {
                    if (in == null) throw new IOException("파일을 열 수 없습니다.");
                    byte[] buffer = new byte[8192]; int count; long total = 0;
                    while ((count = in.read(buffer)) != -1) {
                        total += count; if (total > 64L * 1024 * 1024) throw new IOException("64MB 이하 파일을 선택해 주세요.");
                        out.write(buffer, 0, count);
                    }
                }
                if (ext.equals("hwp") || ext.equals("hwpx")) newHangul = new HangulDocument(local, ext.equals("hwpx"));
                else if (ext.equals("pdf")) newPdf = new PdfDocument(this, local);
                else newExcel = new Spreadsheet(local, ext.equals("xls"));
            } catch (Exception e) {
                if (newExcel != null) newExcel.close(); if (newPdf != null) newPdf.close(); local.delete(); throw e;
            }
            closeDocument();
            input = local; name = display; extension = ext; hangul = newHangul; excel = newExcel; pdf = newPdf;
            page = blockStart = sheet = firstRow = firstCol = 0; dirty = false; revision++;
            getPreferences(MODE_PRIVATE).edit().putString("recent", uri.toString()).apply();
        }, this::showDocument);
    }
    private void showDocument() {
        title.setText("HWP Desk"); subtitle.setText(name); content.removeAllViews(); scroll.scrollTo(0, 0);
        if (hangul != null) showHangul(); else if (excel != null) showExcel(); else if (pdf != null) showPdf();
    }
    private void showHangul() {
        content.removeAllViews();
        content.addView(label("내용 보기 · 문단을 눌러 수정", 14, green));
        LinearLayout nav = horizontal(); content.addView(nav);
        nav.addView(button("이전", () -> { blockStart = Math.max(0, blockStart - 40); showHangul(); }));
        nav.addView(button("다음", () -> { if (blockStart + 40 < hangul.blocks.size()) blockStart += 40; showHangul(); }));
        nav.addView(button("찾기", this::searchHangul));
        int end = Math.min(blockStart + 40, hangul.blocks.size());
        content.addView(label((blockStart + 1) + "–" + end + " / " + hangul.blocks.size() + " 텍스트", 12, green));
        for (int i = blockStart; i < end; i++) {
            TextBlock block = hangul.blocks.get(i);
            LinearLayout card = vertical(); card.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(10)); card.setBackground(bg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8); content.addView(card, params);
            card.addView(label(block.label + (block.editable ? "" : " · 읽기 전용"), 11, green));
            TextView text = label(block.text.isEmpty() ? "(빈 텍스트)" : block.text, 17, ink); card.addView(text);
            card.setOnClickListener(v -> {
                if (busy || !block.editable) return;
                edit("텍스트 수정", block.text, false, value -> job("수정 중…", () -> block.set(value), () -> { edited(); showHangul(); }));
            });
        }
    }
    private void searchHangul() {
        edit("문서에서 찾기", "", false, query -> {
            if (query.isEmpty()) return;
            for (int offset = 1; offset <= hangul.blocks.size(); offset++) {
                int index = (blockStart + offset) % hangul.blocks.size();
                if (hangul.blocks.get(index).text.contains(query)) { blockStart = index; showHangul(); scroll.scrollTo(0, 0); return; }
            }
            message("찾는 내용이 없습니다.");
        });
    }
    private void showExcel() {
        job("시트를 읽는 중…", () -> {
            // Preload only the visible window off the UI thread.
            List<String[]> rows = new ArrayList<>();
            for (int row = firstRow; row < Math.min(firstRow + 20, extension.equals("xls") ? 65536 : 1048576); row++) {
                String[] cells = new String[5];
                for (int col = firstCol; col < Math.min(firstCol + 5, extension.equals("xls") ? 256 : 16384); col++)
                    cells[col - firstCol] = excel.value(sheet, Spreadsheet.address(col, row));
                rows.add(cells);
            }
            runOnUiThread(() -> { if (!destroyed) drawSheet(rows); });
        }, () -> { });
    }
    private void drawSheet(List<String[]> rows) {
        content.removeAllViews();
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, excel.names)); spinner.setSelection(sheet);
        content.addView(spinner);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != sheet && !busy) { sheet = position; firstRow = firstCol = 0; showExcel(); }
            }
        });
        LinearLayout nav = horizontal(); content.addView(nav);
        nav.addView(button("←", () -> { firstCol = Math.max(0, firstCol - 5); showExcel(); }));
        nav.addView(button("→", () -> { firstCol = Math.min(extension.equals("xls") ? 251 : 16379, firstCol + 5); showExcel(); }));
        nav.addView(button("셀 이동", () -> edit("셀 주소 (예: A1)", "", false, value -> {
            try { int[] p = Spreadsheet.coordinate(value.trim());
                if (extension.equals("xls") && (p[0] > 255 || p[1] > 65535)) throw new IllegalArgumentException("XLS 셀 범위를 벗어났습니다.");
                firstCol = p[0]; firstRow = p[1]; showExcel();
            } catch (Exception e) { error(e); }
        })));
        LinearLayout rowNav = horizontal(); content.addView(rowNav);
        rowNav.addView(button("이전 20행", () -> { firstRow = Math.max(0, firstRow - 20); showExcel(); }));
        rowNav.addView(button("다음 20행", () -> { firstRow = Math.min(extension.equals("xls") ? 65516 : 1048556, firstRow + 20); showExcel(); }));
        content.addView(label("셀을 눌러 수정 · =로 시작하면 수식\n수식 계산 결과는 Excel에서 다시 열 때 갱신됩니다.", 12, green));
        HorizontalScrollView horizontalScroll = new HorizontalScrollView(this); TableLayout table = new TableLayout(this); horizontalScroll.addView(table); content.addView(horizontalScroll);
        for (int i = 0; i < rows.size(); i++) {
            TableRow row = new TableRow(this); table.addView(row);
            row.addView(label("" + (firstRow + i + 1), 12, green));
            for (int col = 0; col < 5; col++) {
                String value = rows.get(i)[col]; if (value == null) continue;
                String address = Spreadsheet.address(firstCol + col, firstRow + i);
                TextView cell = label(address + "\n" + value, 14, ink); cell.setWidth(dp(145)); cell.setMinHeight(dp(72)); cell.setPadding(dp(10), dp(8), dp(10), dp(8));
                GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setStroke(dp(1), Color.rgb(223, 227, 220)); cell.setBackground(bg); row.addView(cell);
                cell.setOnClickListener(v -> { if (!busy) edit(address + " 수정", value, true, replacement -> job("셀 수정 중…", () -> excel.set(sheet, address, replacement), () -> { edited(); showExcel(); })); });
            }
        }
    }
    private void showPdf() {
        job("PDF 페이지를 그리는 중…", () -> {
            Bitmap next = pdf.render(page);
            runOnUiThread(() -> {
                if (destroyed) { next.recycle(); return; }
                if (pageBitmap != null) pageBitmap.recycle(); pageBitmap = next;
                drawPdf();
            });
        }, () -> { });
    }
    private void drawPdf() {
        content.removeAllViews();
        content.addView(label((page + 1) + " / " + pdf.count() + " 페이지", 14, green));
        LinearLayout nav = horizontal(); content.addView(nav);
        nav.addView(button("이전", () -> { if (page > 0) { page--; showPdf(); } }));
        nav.addView(button("다음", () -> { if (page + 1 < pdf.count()) { page++; showPdf(); } }));
        nav.addView(button("페이지 이동", () -> edit("페이지 번호", "" + (page + 1), false, value -> {
            try { int n = Integer.parseInt(value); if (n < 1 || n > pdf.count()) throw new IllegalArgumentException("페이지 범위를 벗어났습니다."); page = n - 1; showPdf(); }
            catch (Exception e) { error(e); }
        })));
        LinearLayout tools = horizontal(); content.addView(tools);
        Button pen = button("펜 켜기", () -> { pageCanvas.ink = !pageCanvas.ink; pageCanvas.pendingText = null; });
        pen.setOnClickListener(v -> { if (!busy) { pageCanvas.ink = !pageCanvas.ink; pageCanvas.pendingText = null; pen.setText(pageCanvas.ink ? "펜 끄기" : "펜 켜기"); } });
        tools.addView(pen);
        tools.addView(button("글자 추가", () -> edit("추가할 글자", "", false, value -> { if (!value.isEmpty()) { pageCanvas.pendingText = value; message("페이지에서 글자를 넣을 위치를 누르세요."); } })));
        tools.addView(button("되돌리기", () -> {
            List<PageCanvas.Mark> list = marks.get(page); if (list != null && !list.isEmpty()) { list.remove(list.size() - 1); edited(); pageCanvas.invalidate(); }
        }));
        content.addView(label("글자·필기는 추가 레이어로 저장됩니다.\n펜을 끄면 세로로 스크롤할 수 있습니다.", 12, green));
        pageCanvas = new PageCanvas(this, pageBitmap, marks.computeIfAbsent(page, key -> new ArrayList<>()), this::edited);
        content.addView(pageCanvas, new LinearLayout.LayoutParams(-1, -2));
    }
    private void save(Uri uri) {
        long savedRevision = revision;
        job("수정본을 저장하는 중…", () -> {
            File output = File.createTempFile("export-", "." + extension, getCacheDir());
            try {
                if (hangul != null) hangul.save(output);
                else if (excel != null) excel.save(output);
                else pdf.save(output, marks);
                try (InputStream in = new FileInputStream(output); OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("저장 위치에 쓸 수 없습니다.");
                    byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); out.flush();
                }
            } finally { output.delete(); }
        }, () -> { if (revision == savedRevision) dirty = false; updateStatus(); message("수정본을 저장했습니다."); });
    }
    private interface TextAction { void accept(String text); }
    private void edit(String heading, String value, boolean multiline, TextAction action) {
        EditText text = new EditText(this); text.setText(value); text.setTextSize(17); text.setPadding(dp(18), dp(14), dp(18), dp(14));
        text.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        text.setSingleLine(!multiline);
        new AlertDialog.Builder(this).setTitle(heading).setView(text).setNegativeButton("취소", null).setPositiveButton("적용", (dialog, which) -> action.accept(text.getText().toString())).show();
    }
    private void edited() { dirty = true; revision++; updateStatus(); }
    private void updateStatus() { status.setText(input == null ? "HWP · HWPX · PDF · XLSX · XLS" : extension.toUpperCase(Locale.ROOT) + (dirty ? " · 저장하지 않은 수정 내용" : " · 새 파일로 저장 가능")); }
    private void job(String text, Job job, Runnable after) {
        if (busy || destroyed) return;
        busy = true; status.setText(text); enable(root, false);
        worker.execute(() -> {
            Exception failure = null;
            try { job.run(); } catch (Exception e) { failure = e; }
            final Exception result = failure;
            runOnUiThread(() -> {
                if (destroyed) return;
                busy = false; enable(root, true); updateStatus();
                if (result == null) after.run(); else error(result);
            });
        });
    }
    private void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), enabled); }
    }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void error(Exception error) { new AlertDialog.Builder(this).setTitle("작업을 완료하지 못했습니다").setMessage(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()).setPositiveButton("확인", null).show(); }
    private void discardGuard(Runnable next) {
        if (!dirty) { next.run(); return; }
        new AlertDialog.Builder(this).setTitle("저장하지 않은 내용이 있습니다").setMessage("수정 내용을 닫고 계속할까요?").setNegativeButton("돌아가기", null).setPositiveButton("닫고 계속", (d, w) -> next.run()).show();
    }
    @Override public void onBackPressed() { if (!busy) discardGuard(this::finish); }
    private void closeDocument() {
        if (excel != null) excel.close(); if (pdf != null) pdf.close();
        if (input != null) input.delete();
        hangul = null; excel = null; pdf = null; marks.clear(); pageCanvas = null;
    }
    @Override protected void onDestroy() {
        destroyed = true; worker.execute(this::closeDocument); worker.shutdown();
        if (pageBitmap != null) pageBitmap.recycle();
        super.onDestroy();
    }
}
