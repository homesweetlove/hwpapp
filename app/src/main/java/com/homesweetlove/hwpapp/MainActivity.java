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
    private static final int OPEN = 1, SAVE = 2, MERGE = 3, SIGNATURE = 4, EXPORT_PDF = 5, EXTRACT = 6;
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
    private final int ink = Color.rgb(30, 38, 34), green = Color.rgb(48, 92, 75);
    private interface Job { void run() throws Exception; }
    private interface TextAction { void accept(String value); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = vertical(); root.setBackgroundColor(Color.rgb(245, 244, 240)); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> { root.setPadding(dp(16), insets.getSystemWindowInsetTop() + dp(10), dp(16), insets.getSystemWindowInsetBottom() + dp(10)); return insets; }); root.requestApplyInsets();
        TextView title = label("HWP Desk", 28, ink); title.setTypeface(null, Typeface.BOLD); root.addView(title);
        subtitle = label("내 문서, 내 휴대폰에서", 13, green); root.addView(subtitle);
        LinearLayout actions = horizontal(); root.addView(actions);
        actions.addView(button("열기", () -> guard(() -> picker(OPEN, "*/*")))); actions.addView(button("저장", () -> exportPicker(SAVE)));
        actions.addView(button("↶", () -> travel(false))); actions.addView(button("↷", () -> travel(true))); actions.addView(button("도구", this::tools));
        status = label("HWP · HWPX · PDF · XLSX · XLS", 12, green); root.addView(status);
        scroll = new ScrollView(this); content = vertical(); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        home();
        job("복구 기록 확인 중…", () -> history = new SessionHistory(new File(getFilesDir(), "recovery")), () -> {
            if (getIntent().getData() != null) open(getIntent().getData());
            else if (history.available()) new AlertDialog.Builder(this).setTitle("자동저장 문서 복구").setMessage(history.name() + "의 마지막 작업을 이어서 열까요?").setNegativeButton("나중에", (d, w) -> home()).setPositiveButton("복구", (d, w) -> restore()).show();
        });
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private TextView label(String text, int size, int color) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(6), 0, dp(6)); return v; }
    private Button button(String text, Runnable action) { Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setAllCaps(false); b.setTextColor(green); b.setMinWidth(0); b.setPadding(dp(8), 0, dp(8), 0); b.setOnClickListener(v -> { if (!busy) action.run(); }); return b; }
    private void home() {
        content.removeAllViews();
        content.addView(label("읽고, 고치고,\n다시 이어서 작업하세요", 26, ink));
        content.addView(label("한글: 문단·서식·표·이미지 레이아웃, PDF 변환\n엑셀: 셀·서식·수식 계산\nPDF: 필기·서명·페이지 관리", 16, ink));
        content.addView(label("편집은 자동 저장됩니다. 실행 취소와 다시 실행은 최대 20개 기록을 유지합니다. 원본 대신 수정본을 내보내세요.", 14, green));
        if (history != null && history.available()) content.addView(button("자동저장 문서 복구", this::restore));
    }
    private void picker(int request, String mime) { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i, request); }
    private void exportPicker(int request) {
        if (input == null) { message("먼저 문서를 열어 주세요."); return; }
        String ext = request == SAVE ? extension : "pdf";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(request == SAVE ? mime() : "application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, name.replaceFirst("(?i)\\.[a-z0-9]+$", "") + (request == EXTRACT ? "_추출." : "_수정본.") + ext); startActivityForResult(i, request);
    }
    private String mime() { switch (extension) { case "pdf": return "application/pdf"; case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; case "xls": return "application/vnd.ms-excel"; case "hwpx": return "application/vnd.hancom.hwpx"; default: return "application/x-hwp"; } }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN) { try { if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) {} open(uri); }
        else if (request == SAVE || request == EXPORT_PDF || request == EXTRACT) export(uri, request);
        else if (request == MERGE) mutate("PDF 병합 중…", () -> { flattenPdf(); File f = importFile(uri, ".pdf"); try { pdf.merge(f); } finally { f.delete(); } }, this::show);
        else if (request == SIGNATURE) signature(uri);
    }
    private File importFile(Uri uri, String suffix) throws Exception {
        File local = File.createTempFile("document-", suffix, getCacheDir());
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(local)) {
            if (in == null) throw new IOException("파일을 열 수 없습니다."); byte[] b = new byte[8192]; int n; long total = 0;
            while ((n = in.read(b)) != -1) { total += n; if (total > 64L * 1024 * 1024) throw new IOException("64MB 이하의 파일을 선택하세요."); out.write(b, 0, n); }
        } catch (Exception e) { local.delete(); throw e; } return local;
    }
    private void open(Uri uri) {
        job("문서 여는 중…", () -> {
            String display = "문서";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (cursor != null && cursor.moveToFirst()) display = cursor.getString(0); }
            String ext = display != null && display.contains(".") ? display.substring(display.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!Arrays.asList("hwp", "hwpx", "pdf", "xlsx", "xls").contains(ext)) throw new IOException("HWP·HWPX·PDF·XLSX·XLS 파일을 선택하세요.");
            File local = importFile(uri, "." + ext);
            try {
                // Validate before replacing the previous recovery document.
                if (ext.equals("pdf")) { try (PdfDocument check = new PdfDocument(this, local)) { check.count(); } }
                else if (ext.equals("xls") || ext.equals("xlsx")) { try (Spreadsheet check = new Spreadsheet(local, ext.equals("xls"))) { if (check.names.isEmpty()) throw new IOException("빈 워크북입니다."); } }
                else new HangulDocument(local, ext.equals("hwpx"));
                String finalDisplay = display; history.start(finalDisplay, ext, target -> copy(local, target));
                name = finalDisplay; extension = ext; load(history.current()); resetPosition(); dirty = false;
            } finally { local.delete(); }
        }, this::show);
    }
    private void restore() { job("자동저장 문서 복구 중…", () -> { name = history.name(); extension = history.extension(); load(history.current()); resetPosition(); dirty = true; }, this::show); }
    private void resetPosition() { page = layoutPage = blockStart = sheet = firstRow = firstCol = 0; layoutMode = false; }
    private void load(File from) throws Exception {
        File local = File.createTempFile("working-", "." + extension, getCacheDir()); copy(from, local);
        HangulDocument h = null; Spreadsheet e = null; PdfDocument p = null;
        try { if (extension.equals("hwp") || extension.equals("hwpx")) h = new HangulDocument(local, extension.equals("hwpx")); else if (extension.equals("pdf")) p = new PdfDocument(this, local); else e = new Spreadsheet(local, extension.equals("xls")); }
        catch (Exception failure) { local.delete(); throw failure; }
        closeModel(); input = local; hangul = h; excel = e; pdf = p;
        if (pdf != null) page = Math.max(0, Math.min(page, pdf.count() - 1));
        if (hangul != null) blockStart = Math.max(0, Math.min(blockStart, Math.max(0, hangul.blocks.size() - 1)));
    }
    private void writeCurrent(File target) throws Exception { if (hangul != null) hangul.save(target); else if (excel != null) excel.save(target); else pdf.save(target, marks); }
    private void mutate(String text, Job mutation, Runnable redraw) {
        job(text, () -> {
            try { mutation.run(); history.commit(this::writeCurrent); load(history.current()); dirty = true; }
            catch (Exception failure) { if (history.available()) load(history.current()); throw failure; }
        }, redraw, true);
    }
    private void travel(boolean redo) {
        if (input == null || !(redo ? history.canRedo() : history.canUndo())) { message("이동할 편집 기록이 없습니다."); return; }
        job(redo ? "다시 실행 중…" : "실행 취소 중…", () -> { load(redo ? history.redo() : history.undo()); dirty = true; }, this::show);
    }
    private void show() { subtitle.setText(name); scroll.scrollTo(0, 0); if (hangul != null) showHangul(); else if (excel != null) showExcel(); else if (pdf != null) showPdf(); else home(); }
    private void tools() {
        if (input == null) { message("먼저 문서를 열어 주세요."); return; }
        if (hangul != null) menu("한글 도구", new String[]{"레이아웃 보기", "PDF로 내보내기", "찾기·바꾸기", "자동저장 기록 삭제"}, index -> {
            if (index == 0) preview(); else if (index == 1) exportPicker(EXPORT_PDF); else if (index == 2) replaceDialog(); else clearRecovery();
        });
        else if (excel != null) menu("엑셀 도구", new String[]{"찾기·바꾸기", "현재 시트에서 찾기", "자동저장 기록 삭제"}, index -> { if (index == 0) replaceDialog(); else if (index == 1) searchSheet(); else clearRecovery(); });
        else menu("PDF 도구", new String[]{"서명 이미지 넣기", "현재 페이지 90° 회전", "현재 페이지 삭제", "페이지 순서 변경", "다른 PDF 합치기", "페이지 추출·분할", "자동저장 기록 삭제"}, index -> {
            if (index == 0) picker(SIGNATURE, "image/*");
            else if (index == 1) mutate("페이지 회전 중…", () -> { flattenPdf(); pdf.rotate(page); }, this::show);
            else if (index == 2) new AlertDialog.Builder(this).setTitle("" + (page + 1) + "페이지 삭제").setMessage("실행 취소로 복구할 수 있습니다.").setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> mutate("페이지 삭제 중…", () -> { flattenPdf(); pdf.delete(page); }, this::show)).show();
            else if (index == 3) edit("새 순서 (예: 3,1,2 또는 3-1)", "1-" + pdf.count(), false, value -> mutate("순서 변경 중…", () -> { List<Integer> order = PageRanges.parse(value, pdf.count(), true); flattenPdf(); pdf.reorder(order); page = 0; }, this::show));
            else if (index == 4) picker(MERGE, "application/pdf");
            else if (index == 5) edit("내보낼 페이지 (예: 1-3,5)", "" + (page + 1), false, value -> { try { extraction = PageRanges.parse(value, pdf.count(), false); exportPicker(EXTRACT); } catch (Exception e) { error(e); } });
            else clearRecovery();
        });
    }
    private interface Choice { void choose(int index); }
    private void menu(String title, String[] items, Choice action) { new AlertDialog.Builder(this).setTitle(title).setItems(items, (d, i) -> { if (!busy) action.choose(i); }).show(); }
    private void clearRecovery() { new AlertDialog.Builder(this).setTitle("자동저장 기록 삭제").setMessage("문서를 닫고 저장된 복구·변경 이력을 삭제합니다. 내보낸 파일은 유지됩니다.").setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> job("삭제 중…", () -> { closeModel(); history.clear(); dirty = false; }, () -> { subtitle.setText("내 문서, 내 휴대폰에서"); home(); })).show(); }
    private void replaceDialog() {
        LinearLayout fields = vertical(); EditText find = field("찾을 내용", ""), replacement = field("바꿀 내용", ""); fields.addView(find); fields.addView(replacement);
        new AlertDialog.Builder(this).setTitle("모두 바꾸기").setView(fields).setNegativeButton("취소", null).setPositiveButton("적용", (d, w) -> {
            int[] changed = {0}; mutate("내용 바꾸는 중…", () -> changed[0] = hangul != null ? hangul.replace(find.getText().toString(), replacement.getText().toString()) : excel.replace(find.getText().toString(), replacement.getText().toString()), () -> { show(); message(changed[0] + "개 항목을 수정했습니다."); });
        }).show();
    }
    private void showHangul() {
        layoutMode = false; content.removeAllViews();
        LinearLayout nav = horizontal(); content.addView(nav); nav.addView(button("이전", () -> { blockStart = Math.max(0, blockStart - 40); showHangul(); })); nav.addView(button("다음", () -> { if (blockStart + 40 < hangul.blocks.size()) blockStart += 40; showHangul(); })); nav.addView(button("찾기", this::searchHangul)); nav.addView(button("레이아웃", this::preview));
        int end = Math.min(blockStart + 40, hangul.blocks.size()); content.addView(label((blockStart + 1) + "–" + end + " / " + hangul.blocks.size() + " · 문단을 눌러 편집", 12, green));
        for (int index = blockStart; index < end; index++) {
            TextBlock block = hangul.blocks.get(index); LinearLayout card = vertical(); card.setPadding(dp(12), dp(8), dp(12), dp(8));
            GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(10)); card.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(8); content.addView(card, params);
            card.addView(label(block.label + (block.editable ? "" : " · 읽기 전용"), 11, green)); TextView text = label(block.text.isEmpty() ? "(빈 문단)" : block.text, 17, ink); card.addView(text);
            try { DocumentStyle style = hangul.style(block); text.setTextSize(style.size + 3); text.setTypeface(Typeface.create(style.font, style.bold ? Typeface.BOLD : Typeface.NORMAL)); text.setGravity(style.alignment.equals("center") ? Gravity.CENTER_HORIZONTAL : style.alignment.equals("right") ? Gravity.END : Gravity.START); } catch (Exception ignored) {}
            card.setOnClickListener(v -> { if (!busy && block.editable) menu("문단 편집", new String[]{"텍스트 수정", "글꼴·크기·굵게·정렬", "아래에 문단 추가", "문단 삭제"}, choice -> {
                if (choice == 0) edit("텍스트 수정", block.text, false, value -> mutate("수정 중…", () -> block.set(value), this::show));
                else if (choice == 1) { try { styleDialog(hangul.style(block), false, style -> mutate("서식 적용 중…", () -> hangul.format(block, style), this::show)); } catch (Exception e) { error(e); } }
                else if (choice == 2) edit("새 문단 내용", "", false, value -> mutate("문단 추가 중…", () -> hangul.insertAfter(block, value), this::show));
                else mutate("문단 삭제 중…", () -> hangul.deleteParagraph(block), this::show);
            }); });
        }
    }
    private void searchHangul() { edit("문서에서 찾기", "", false, query -> { if (query.isEmpty()) return; for (int offset = 1; offset <= hangul.blocks.size(); offset++) { int i = (blockStart + offset) % hangul.blocks.size(); if (hangul.blocks.get(i).text.contains(query)) { blockStart = i; showHangul(); scroll.scrollTo(0, 0); return; } } message("찾는 내용이 없습니다."); }); }
    private void preview() {
        job("문서 레이아웃 만드는 중…", () -> { prepareLayout(); layoutMode = true; layoutPage = 0; }, this::showLayout);
    }
    private void prepareLayout() throws Exception {
        if (layoutPdf != null) layoutPdf.close(); if (layoutFile != null) layoutFile.delete();
        layoutFile = File.createTempFile("layout-", ".pdf", getCacheDir()); HangulRenderer renderer = new HangulRenderer(hangul); renderer.export(layoutFile);
        layoutNotice = renderer.notices.isEmpty() ? "기기 글꼴로 배치합니다. 한컴의 인쇄 배치와 차이가 날 수 있습니다." : android.text.TextUtils.join("\n", renderer.notices);
        layoutPdf = new PdfDocument(this, layoutFile);
    }
    private void showLayout() {
        job("레이아웃 페이지 표시 중…", () -> {
            Bitmap next = layoutPdf.render(layoutPage); runOnUiThread(() -> { if (destroyed) { next.recycle(); return; } setBitmap(next); content.removeAllViews();
                LinearLayout nav = horizontal(); content.addView(nav); nav.addView(button("텍스트", this::showHangul)); nav.addView(button("이전", () -> { if (layoutPage > 0) { layoutPage--; showLayout(); } })); nav.addView(button("다음", () -> { if (layoutPage + 1 < layoutPdf.count()) { layoutPage++; showLayout(); } })); nav.addView(button("PDF 저장", () -> exportPicker(EXPORT_PDF)));
                content.addView(label("레이아웃 " + (layoutPage + 1) + " / " + layoutPdf.count(), 12, green)); content.addView(label(layoutNotice, 12, green)); ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setImageBitmap(next); content.addView(image, new LinearLayout.LayoutParams(-1, -2));
            });
        }, () -> {});
    }
    private void showExcel() {
        job("시트 계산 중…", () -> {
            List<String[]> rows = new ArrayList<>(); List<DocumentStyle[]> styles = new ArrayList<>();
            for (int row = firstRow; row < Math.min(firstRow + 20, extension.equals("xls") ? 65536 : 1048576); row++) {
                String[] values = new String[5]; DocumentStyle[] formats = new DocumentStyle[5];
                for (int col = firstCol; col < Math.min(firstCol + 5, extension.equals("xls") ? 256 : 16384); col++) { String address = Spreadsheet.address(col, row); values[col - firstCol] = excel.display(sheet, address); formats[col - firstCol] = excel.style(sheet, address); }
                rows.add(values); styles.add(formats);
            }
            runOnUiThread(() -> { if (!destroyed) drawSheet(rows, styles); });
        }, () -> {});
    }
    private void drawSheet(List<String[]> rows, List<DocumentStyle[]> styles) {
        content.removeAllViews(); Spinner spinner = new Spinner(this); spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, excel.names)); spinner.setSelection(sheet); content.addView(spinner);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { public void onNothingSelected(AdapterView<?> parent) {} public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (!busy && sheet != position) { sheet = position; firstRow = firstCol = 0; showExcel(); } } });
        LinearLayout nav = horizontal(); content.addView(nav); nav.addView(button("←", () -> { firstCol = Math.max(0, firstCol - 5); showExcel(); })); nav.addView(button("→", () -> { firstCol = Math.min(extension.equals("xls") ? 251 : 16379, firstCol + 5); showExcel(); })); nav.addView(button("셀 이동", () -> edit("셀 주소", "A1", false, address -> jump(address)))); nav.addView(button("찾기", this::searchSheet));
        LinearLayout verticalNav = horizontal(); content.addView(verticalNav); verticalNav.addView(button("이전 20행", () -> { firstRow = Math.max(0, firstRow - 20); showExcel(); })); verticalNav.addView(button("다음 20행", () -> { firstRow = Math.min(extension.equals("xls") ? 65516 : 1048556, firstRow + 20); showExcel(); }));
        content.addView(label("값·계산 결과 보기 · 셀을 눌러 원문·수식·서식 편집\n지원하지 않는 수식은 #UNSUPPORTED로 표시합니다.", 12, green));
        HorizontalScrollView area = new HorizontalScrollView(this); TableLayout table = new TableLayout(this); area.addView(table); content.addView(area);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            TableRow row = new TableRow(this); table.addView(row); row.addView(label("" + (firstRow + rowIndex + 1), 12, green));
            for (int colIndex = 0; colIndex < 5; colIndex++) {
                if (rows.get(rowIndex)[colIndex] == null) continue; String address = Spreadsheet.address(firstCol + colIndex, firstRow + rowIndex); DocumentStyle style = styles.get(rowIndex)[colIndex];
                TextView cell = label(address + "\n" + rows.get(rowIndex)[colIndex], Math.round(style.size + 2), ink); cell.setWidth(dp(145)); cell.setMinHeight(dp(72)); cell.setPadding(dp(8), dp(8), dp(8), dp(8)); cell.setTypeface(Typeface.create(style.font, style.bold ? Typeface.BOLD : Typeface.NORMAL)); cell.setGravity(style.alignment.equals("right") ? Gravity.END : style.alignment.equals("center") ? Gravity.CENTER_HORIZONTAL : Gravity.START);
                GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setStroke(dp(1), Color.rgb(223, 227, 220)); cell.setBackground(bg); row.addView(cell);
                cell.setOnClickListener(v -> { if (!busy) menu(address, new String[]{"값·수식 수정", "글꼴·정렬·표시 형식"}, choice -> {
                    if (choice == 0) job("셀 원문 읽는 중…", () -> { String raw = excel.value(sheet, address); runOnUiThread(() -> { if (!destroyed) edit(address + " 수정", raw, true, value -> mutate("셀 수정 중…", () -> excel.set(sheet, address, value), this::show)); }); }, () -> {});
                    else styleDialog(style, true, format -> mutate("셀 서식 적용 중…", () -> excel.format(sheet, address, format), this::show));
                }); });
            }
        }
    }
    private void jump(String address) { try { int[] pos = Spreadsheet.coordinate(address.trim()); if (extension.equals("xls") && (pos[0] > 255 || pos[1] > 65535)) throw new IOException("XLS 범위를 벗어났습니다."); firstCol = pos[0]; firstRow = pos[1]; showExcel(); } catch (Exception e) { error(e); } }
    private void searchSheet() { edit("현재 시트에서 찾기", "", false, query -> { String[] found = {null}; job("셀 찾는 중…", () -> { for (String address : excel.addresses(sheet)) if (excel.value(sheet, address).contains(query)) { found[0] = address; break; } }, () -> { if (found[0] != null) jump(found[0]); else message("찾는 내용이 없습니다."); }); }); }
    private void showPdf() {
        job("PDF 표시 중…", () -> { Bitmap next = pdf.render(page); runOnUiThread(() -> { if (destroyed) { next.recycle(); return; } setBitmap(next); drawPdf(); }); }, () -> {});
    }
    private void drawPdf() {
        content.removeAllViews(); LinearLayout nav = horizontal(); content.addView(nav);
        nav.addView(button("이전", () -> { if (page > 0) { page--; showPdf(); } })); nav.addView(button("다음", () -> { if (page + 1 < pdf.count()) { page++; showPdf(); } })); nav.addView(button("페이지", () -> edit("페이지 번호", "" + (page + 1), false, value -> { try { int n = Integer.parseInt(value); if (n < 1 || n > pdf.count()) throw new IOException("페이지 범위를 벗어났습니다."); page = n - 1; showPdf(); } catch (Exception e) { error(e); } })));
        content.addView(label((page + 1) + " / " + pdf.count() + " 페이지 · 도구에서 서명·페이지 관리", 12, green));
        LinearLayout tools = horizontal(); content.addView(tools); Button pen = button("펜 켜기", () -> {}); tools.addView(pen);
        pen.setOnClickListener(v -> { if (!busy) { pageCanvas.ink = !pageCanvas.ink; inkMode = pageCanvas.ink; pageCanvas.pendingText = null; pageCanvas.pendingImage = null; pen.setText(pageCanvas.ink ? "펜 끄기" : "펜 켜기"); } });
        tools.addView(button("글자", () -> edit("추가할 글자", "", false, value -> { if (!value.isEmpty()) { pageCanvas.pendingText = value; pageCanvas.pendingImage = null; pageCanvas.ink = false; inkMode = false; pen.setText("펜 켜기"); message("넣을 위치를 누르세요."); } })));
        tools.addView(button("서명", () -> picker(SIGNATURE, "image/*")));
        content.addView(label("한 번의 필기·삽입마다 자동 저장합니다. 펜을 끄면 스크롤할 수 있습니다.", 12, green));
        pageCanvas = new PageCanvas(this, pageBitmap, marks.computeIfAbsent(page, key -> new ArrayList<>()), () -> mutate("필기 자동저장 중…", () -> {}, this::show)); pageCanvas.ink = inkMode; pen.setText(inkMode ? "펜 끄기" : "펜 켜기"); content.addView(pageCanvas, new LinearLayout.LayoutParams(-1, -2));
    }
    private void signature(Uri uri) {
        Bitmap[] bitmap = {null}; job("서명 이미지 읽는 중…", () -> {
            File file = importFile(uri, ".image"); try { BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getAbsolutePath(), options); if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("이미지를 읽을 수 없습니다."); options.inJustDecodeBounds = false; options.inSampleSize = Math.max(1, (int) Math.ceil(Math.max(options.outWidth, options.outHeight) / 1200f)); bitmap[0] = BitmapFactory.decodeFile(file.getAbsolutePath(), options); if (bitmap[0] == null) throw new IOException("이미지를 읽을 수 없습니다."); } finally { file.delete(); }
        }, () -> edit("서명 너비: 페이지의 % (5–90)", "25", false, value -> { try { float ratio = Float.parseFloat(value) / 100; if (!Float.isFinite(ratio) || ratio < .05 || ratio > .9) throw new IOException("5–90 사이의 값을 입력하세요."); pageCanvas.signatureWidth = ratio; pageCanvas.pendingImage = bitmap[0]; pageCanvas.ink = false; inkMode = false; pageCanvas.pendingText = null; message("페이지에서 서명을 넣을 위치를 누르세요."); } catch (Exception e) { bitmap[0].recycle(); error(e); } }));
    }
    private void flattenPdf() throws Exception { if (marks.isEmpty()) return; File file = File.createTempFile("flatten-", ".pdf", getCacheDir()); try { pdf.save(file, marks); PdfDocument next = new PdfDocument(this, file); pdf.close(); pdf = next; marks.clear(); } finally { file.delete(); } }
    private void export(Uri uri, int request) {
        job("파일 내보내는 중…", () -> {
            File output = File.createTempFile("export-", request == SAVE ? "." + extension : ".pdf", getCacheDir());
            try {
                if (request == SAVE) writeCurrent(output);
                else if (request == EXPORT_PDF) { HangulRenderer renderer = new HangulRenderer(hangul); renderer.export(output); }
                else { flattenPdf(); pdf.extract(output, extraction); }
                try (InputStream in = new FileInputStream(output); OutputStream out = getContentResolver().openOutputStream(uri, "wt")) { if (out == null) throw new IOException("저장 위치에 쓸 수 없습니다."); transfer(in, out); }
                if (request == SAVE) dirty = false;
            } finally { output.delete(); }
        }, () -> { message("파일을 저장했습니다."); updateStatus(); });
    }
    private interface StyleAction { void accept(DocumentStyle style); }
    private void styleDialog(DocumentStyle initial, boolean numbers, StyleAction action) {
        LinearLayout form = vertical(); EditText font = field("글꼴 이름", initial.font), size = field("크기(pt)", "" + initial.size); size.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); form.addView(font); form.addView(size);
        CheckBox bold = new CheckBox(this); bold.setText("굵게"); bold.setChecked(initial.bold); form.addView(bold);
        Spinner alignment = new Spinner(this); String[] aligns = {"left", "center", "right"}; alignment.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"왼쪽", "가운데", "오른쪽"})); alignment.setSelection(Arrays.asList(aligns).indexOf(initial.alignment)); form.addView(alignment);
        Spinner format = new Spinner(this); String[] formats = {"general", "number", "currency", "date", "percent"}; if (numbers) { format.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"일반", "숫자", "금액", "날짜", "백분율"})); format.setSelection(Arrays.asList(formats).indexOf(initial.numberFormat)); form.addView(format); }
        new AlertDialog.Builder(this).setTitle("서식 편집").setView(form).setNegativeButton("취소", null).setPositiveButton("적용", (d, w) -> { try { DocumentStyle style = initial.copy(); style.font = font.getText().toString().trim(); style.size = Float.parseFloat(size.getText().toString()); style.bold = bold.isChecked(); style.alignment = aligns[alignment.getSelectedItemPosition()]; if (numbers) style.numberFormat = formats[format.getSelectedItemPosition()]; style.validate(); action.accept(style); } catch (Exception e) { error(e); } }).show();
    }
    private EditText field(String hint, String value) { EditText v = new EditText(this); v.setHint(hint); v.setText(value); v.setSingleLine(true); v.setPadding(dp(16), dp(8), dp(16), dp(8)); return v; }
    private void edit(String title, String value, boolean multiline, TextAction action) { EditText v = field(title, value); v.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0)); v.setSingleLine(!multiline); new AlertDialog.Builder(this).setTitle(title).setView(v).setNegativeButton("취소", null).setPositiveButton("적용", (d, w) -> { if (!busy) action.accept(v.getText().toString()); }).show(); }
    private void guard(Runnable next) { if (!dirty) { next.run(); return; } new AlertDialog.Builder(this).setTitle("다른 문서 열기").setMessage("현재 내용은 자동저장되어 있습니다. 새 문서를 열면 복구 기록이 새 문서로 바뀝니다. 필요한 수정본은 먼저 저장하세요.").setNegativeButton("돌아가기", null).setPositiveButton("계속", (d, w) -> next.run()).show(); }
    private void job(String text, Job action, Runnable after) { job(text, action, after, false); }
    private void job(String text, Job action, Runnable after, boolean refreshOnFailure) {
        if (busy || destroyed) return; busy = true; status.setText(text); enable(root, false);
        worker.execute(() -> { Exception failure = null; try { action.run(); } catch (Exception e) { failure = e; } final Exception result = failure;
            runOnUiThread(() -> { if (destroyed) return; busy = false; enable(root, true); updateStatus(); if (result == null) after.run(); else { error(result); if (refreshOnFailure && input != null) show(); } }); });
    }
    private void updateStatus() { status.setText(input == null ? "HWP · HWPX · PDF · XLSX · XLS" : extension.toUpperCase(Locale.ROOT) + (dirty ? " · 수정됨 · 자동저장 완료" : " · 자동저장 완료")); }
    private void enable(View view, boolean value) { view.setEnabled(value); if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), value); } }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void error(Exception e) { new AlertDialog.Builder(this).setTitle("작업을 완료하지 못했습니다").setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).setPositiveButton("확인", null).show(); }
    private void setBitmap(Bitmap next) { content.removeAllViews(); if (pageBitmap != null) pageBitmap.recycle(); pageBitmap = next; }
    private void closeModel() { if (excel != null) excel.close(); if (pdf != null) pdf.close(); if (layoutPdf != null) layoutPdf.close(); if (input != null) input.delete(); if (layoutFile != null) layoutFile.delete(); hangul = null; excel = null; pdf = layoutPdf = null; input = layoutFile = null; marks.clear(); pageCanvas = null; layoutMode = false; }
    private static void copy(File from, File to) throws IOException { try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { transfer(in, out); } }
    private static void transfer(InputStream in, OutputStream out) throws IOException { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); out.flush(); }
    @Override public void onBackPressed() { if (busy) return; if (layoutMode) { showHangul(); return; } finish(); }
    @Override protected void onDestroy() { destroyed = true; worker.execute(this::closeModel); worker.shutdown(); if (pageBitmap != null) pageBitmap.recycle(); super.onDestroy(); }
}
