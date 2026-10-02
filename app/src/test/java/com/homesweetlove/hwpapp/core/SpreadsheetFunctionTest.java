package com.homesweetlove.hwpapp.core;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

public class SpreadsheetFunctionTest {
  private static Object eval(String formula) {
    Map<String, Object> cells = new HashMap<>();
    cells.put("A1", 1d);
    cells.put("A2", 2d);
    cells.put("A3", 3d);
    cells.put("B1", "식비");
    cells.put("B2", "교통");
    cells.put("B3", "식비");
    cells.put("C1", 100d);
    cells.put("C2", 200d);
    cells.put("C3", 300d);
    return FormulaEngine.evaluate(formula, (sheet, ref) -> cells.get(ref));
  }

  private static void check(String expected, String formula) {
    assertEquals(formula, expected, FormulaEngine.text(eval(formula)));
  }

  @Test
  public void excelOperatorPrecedenceErrorsAndTypedComparisons() {
    check("64", "=2^3^2");
    check("4", "=-2^2");
    assertEquals(Math.pow(2, .03), ((Number) eval("=2^3%")).doubleValue(), 1e-12);
    check("TRUE", "=2<>\"2\"");
    check("3", "=\"2\"+1");
    check("7", "=IFERROR(#N/A,7)");
    check("#DIV/0!", "=1/0");
    check("#NUM!", "=SQRT(-1)");
    check("#UNSUPPORTED(UNKNOWN)", "=UNKNOWN(A1)");
    check("4", "=SUM(\"3\",TRUE)");
    check("6", "=SUM(A1:A3)");
  }

  @Test
  public void conditionalTotalsAndWildcardCriteria() {
    check("400", "=SUMIF(B1:B3,\"식비\",C1:C3)");
    check("300", "=SUMIFS(C1:C3,B1:B3,\"식*\",A1:A3,\">1\")");
    check("2", "=COUNTIF(B1:B3,\"식?\")");
    check("1", "=COUNTIFS(B1:B3,\"식비\",C1:C3,\">200\")");
    check("200", "=AVERAGEIF(B1:B3,\"식비\",C1:C3)");
    check("300", "=AVERAGEIFS(C1:C3,B1:B3,\"식비\",A1:A3,\">1\")");
    check("#DIV/0!", "=AVERAGEIF(B1:B3,\"없음\",C1:C3)");
    check("#VALUE!", "=SUMIFS(C1:C3,B1:B2,\"식비\")");
    check("0", "=COUNTIF(B1:B3,\"~*\")");
  }

  @Test
  public void lookupFamiliesAndUnsupportedModes() {
    check("교통", "=VLOOKUP(2,A1:C3,2,FALSE)");
    check("200", "=VLOOKUP(2.5,A1:C3,3,TRUE)");
    check("3", "=HLOOKUP(1,A1:C3,3,FALSE)");
    check("식비", "=INDEX(B1:C3,3,1)");
    check("3", "=MATCH(3,A1:A3,0)");
    check("2", "=MATCH(2.5,A1:A3,1)");
    check("200", "=XLOOKUP(2,A1:A3,C1:C3)");
    check("300", "=XLOOKUP(\"식비\",B1:B3,C1:C3,0,0,-1)");
    check("100", "=_xlfn.XLOOKUP(\"식*\",B1:B3,C1:C3,0,2)");
    check("없음", "=XLOOKUP(8,A1:A3,C1:C3,\"없음\")");
    check("#UNSUPPORTED(XLOOKUP_MODE)", "=XLOOKUP(2.5,A1:A3,C1:C3,0,-1)");
    check("#REF!", "=VLOOKUP(1,A1:C3,4,FALSE)");
  }

  @Test
  public void stringsAreUnicodeAndBounded() {
    check("4", "=LEN(\"안녕하세요\")-1");
    check("안녕", "=LEFT(\"안녕하세요\",2)");
    check("세요", "=RIGHT(\"안녕하세요\",2)");
    check("녕하", "=MID(\"안녕하세요\",2,2)");
    check("AB", "=UPPER(\"ab\")");
    check("ab", "=LOWER(\"AB\")");
    check("a b", "=TRIM(\" a   b \")");
    check("xyx", "=SUBSTITUTE(\"xxx\",\"x\",\"y\",2)");
    check("axc", "=REPLACE(\"abc\",2,1,\"x\")");
    check("2", "=FIND(\"b\",\"abc\")");
    check("2", "=SEARCH(\"B\",\"abc\")");
    check("FALSE", "=EXACT(\"a\",\"A\")");
    check("1234.5", "=VALUE(\"1,234.5\")");
    check("1,234.50", "=TEXT(1234.5,\"#,##0.00\")");
    check("1-2-3", "=TEXTJOIN(\"-\",TRUE,A1:A3)");
    check("식비교통식비", "=CONCAT(B1:B3)");
    check("ab", "=CONCATENATE(\"a\",\"b\")");
    check("aaa", "=REPT(\"a\",3)");
    check("#LIMIT!", "=REPT(\"a\",40000)");
  }

  @Test
  public void datesFollowWorkbookEpochAndLeapYear() {
    check("61", "=DATE(1900,3,1)");
    check("60", "=DATE(1900,2,29)");
    check("29", "=DAY(60)");
    check("1900", "=YEAR(61)");
    check("3", "=MONTH(61)");
    check("1", "=DAYS(61,60)");
    check("92", "=EDATE(61,1)");
    check("91", "=EOMONTH(61,0)");
    check("5", "=WEEKDAY(61)");
    check("4", "=WEEKDAY(61,2)");
    check("0.5", "=TIME(12,0,0)");
    check("12", "=HOUR(0.5)");
    check("30", "=MINUTE(TIME(12,30,0))");
    check("15", "=SECOND(TIME(0,0,15))");
    Object result =
        FormulaEngine.evaluate(
            "=DATE(1904,1,1)",
            new FormulaEngine.Resolver() {
              public Object cell(String s, String r) {
                return null;
              }

              public boolean date1904() {
                return true;
              }
            });
    assertEquals("0", FormulaEngine.text(result));
  }

  @Test
  public void mathStatisticsLogicAndInspection() {
    String[][] fixtures = {
      {"6", "PRODUCT(A1:A3)"},
      {"14", "SUMSQ(A1:A3)"},
      {"3", "COUNTA(B1:B3)"},
      {"2", "COUNTBLANK(D1:D2)"},
      {"2", "MEDIAN(A1:A3)"},
      {"2", "LARGE(A1:A3,2)"},
      {"1", "SMALL(A1:A3,1)"},
      {"1", "RANK(3,A1:A3)"},
      {"1", "RANK.EQ(3,A1:A3)"},
      {"1", "STDEV.S(A1:A3)"},
      {"1", "VAR.S(A1:A3)"},
      {"2.6666666666666665", "VAR.P(A1:A3)*4"},
      {"1.24", "ROUNDUP(1.231,2)"},
      {"1.23", "ROUNDDOWN(1.239,2)"},
      {"6", "MROUND(5,2)"},
      {"-2", "INT(-1.1)"},
      {"-1", "TRUNC(-1.9)"},
      {"1", "MOD(-3,2)"},
      {"2", "QUOTIENT(5,2)"},
      {"-1", "SIGN(-5)"},
      {"8", "POWER(2,3)"},
      {"3", "SQRT(9)"},
      {"2", "LOG(100)"},
      {"2", "LOG10(100)"},
      {"0", "LN(1)"},
      {"1", "EXP(0)"},
      {"0", "SIN(0)"},
      {"1", "COS(0)"},
      {"0", "TAN(0)"},
      {"0", "ASIN(0)"},
      {"0", "ACOS(1)"},
      {"0", "ATAN(0)"},
      {"180", "DEGREES(PI())"},
      {"6", "CEILING(5,2)"},
      {"4", "FLOOR(5,2)"},
      {"TRUE", "NOT(FALSE)"},
      {"TRUE", "XOR(TRUE,FALSE)"},
      {"ok", "IFS(FALSE,\"bad\",TRUE,\"ok\")"},
      {"b", "CHOOSE(2,\"a\",\"b\")"},
      {"b", "SWITCH(2,1,\"a\",2,\"b\",\"c\")"},
      {"3", "IFNA(NA(),3)"},
      {"TRUE", "ISNUMBER(2)"},
      {"TRUE", "ISTEXT(\"a\")"},
      {"TRUE", "ISBLANK(D1)"},
      {"TRUE", "ISERROR(1/0)"},
      {"TRUE", "ISERR(1/0)"},
      {"TRUE", "ISNA(NA())"},
      {"TRUE", "ISLOGICAL(TRUE)"},
      {"0", "N(\"abc\")"},
      {"", "T(12)"},
      {"3", "ROWS(A1:C3)"},
      {"3", "COLUMNS(A1:C3)"}
    };
    for (String[] fixture : fixtures) check(fixture[0], "=" + fixture[1]);
    assertEquals(Math.sqrt(2d / 3), ((Number) eval("=STDEV.P(A1:A3)")).doubleValue(), 1e-12);
    assertEquals(Math.PI, ((Number) eval("=RADIANS(180)")).doubleValue(), 1e-12);
    assertTrue(eval("=TODAY()") instanceof Number);
    assertTrue(eval("=NOW()") instanceof Number);
  }

  @Test
  public void newWorkbookRangeClipboardAndSheetsSurviveReload() throws Exception {
    File input = File.createTempFile("fresh-", ".xlsx"),
        output = File.createTempFile("fresh-out-", ".xlsx");
    NewDocuments.spreadsheet(input, true);
    try (Spreadsheet doc = new Spreadsheet(input, false)) {
      assertEquals(
          "800000", doc.display(0, "B5").replace("₩", "").replace(",", "").replace(".00", ""));
      doc.paste(0, "F1", "\"줄\n둘\"\t\"따옴표\"\"\"\r\n3\t=F2*2");
      String copied = doc.copyRange(0, "F1:G2", false);
      doc.paste(0, "I1", copied);
      assertEquals("줄\n둘", doc.value(0, "I1"));
      assertEquals("따옴표\"", doc.value(0, "J1"));
      assertEquals("6", doc.display(0, "G2"));
      doc.addSheet("두 번째");
      doc.set(1, "$a$1", "='가계부'!F2+1");
      assertEquals("4", doc.display(1, "A1"));
      doc.save(output);
    }
    try (Spreadsheet doc = new Spreadsheet(output, false)) {
      assertEquals(2, doc.names.size());
      assertEquals("4", doc.display(1, "A1"));
      assertEquals("줄\n둘", doc.value(0, "I1"));
    }
    input.delete();
    output.delete();
  }

  @Test
  public void invalidRangesClipboardAndSheetNamesReject() throws Exception {
    for (String s : Arrays.asList("A0", "XFE1", "A1:A20000", "A1:B2:C3")) {
      try {
        Spreadsheet.range(s);
        fail(s);
      } catch (IllegalArgumentException expected) {
      }
    }
    try {
      TabularText.parse("\"unclosed");
      fail();
    } catch (IllegalArgumentException expected) {
    }
    File f = File.createTempFile("names-", ".xlsx");
    NewDocuments.spreadsheet(f, false);
    try (Spreadsheet doc = new Spreadsheet(f, false)) {
      for (String name : Arrays.asList("", "Sheet1", "a/b", "a:b", "a[b")) {
        try {
          doc.addSheet(name);
          fail(name);
        } catch (IOException expected) {
        }
      }
    }
    f.delete();
  }

  @Test
  public void originalRemainsAfterHistoryTrimming() throws Exception {
    File dir = Files.createTempDirectory("original-").toFile();
    SessionHistory h = new SessionHistory(dir);
    h.start("test.hwp", "hwp", file -> Files.write(file.toPath(), new byte[] {1}));
    for (int i = 0; i < 25; i++) h.commit(file -> Files.write(file.toPath(), new byte[] {2}));
    SessionHistory reload = new SessionHistory(dir);
    assertEquals(1, Files.readAllBytes(reload.original().toPath())[0]);
    assertEquals(2, Files.readAllBytes(reload.current().toPath())[0]);
    reload.clear();
    dir.delete();
  }

  @Test
  public void sumProductAndFinancialFunctionsRoundTripNumerically() {
    check("1400", "=SUMPRODUCT(A1:A3,C1:C3)");
    check("-100", "=PMT(0,10,1000)");
    check("1000", "=PV(0,10,-100)");
    check("1000", "=FV(0,10,-100)");
    check("10", "=NPER(0,-100,1000)");
    assertEquals(10000, ((Number) eval("=PV(0.01,12,PMT(0.01,12,10000))")).doubleValue(), 1e-8);
    assertEquals(12, ((Number) eval("=NPER(0.01,PMT(0.01,12,10000),10000)")).doubleValue(), 1e-8);
    assertEquals(0, ((Number) eval("=FV(0.01,12,PMT(0.01,12,10000),10000)")).doubleValue(), 1e-8);
  }
}
