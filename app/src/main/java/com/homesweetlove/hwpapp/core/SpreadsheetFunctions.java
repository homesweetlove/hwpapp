package com.homesweetlove.hwpapp.core;

import static com.homesweetlove.hwpapp.core.FormulaEngine.*;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit, bounded subset of Excel functions. No silent cached-value fallback. */
public final class SpreadsheetFunctions {
  private static final String EXTRA =
      "SUMPRODUCT PMT PV FV NPER PRODUCT SUMSQ COUNTA COUNTBLANK MEDIAN LARGE SMALL RANK RANK.EQ"
          + " STDEV.S STDEV.P VAR.S VAR.P SUMIF SUMIFS COUNTIF COUNTIFS AVERAGEIF AVERAGEIFS"
          + " VLOOKUP HLOOKUP XLOOKUP INDEX MATCH CHOOSE IFS IFNA NOT XOR SWITCH ROUNDUP ROUNDDOWN"
          + " MROUND INT TRUNC MOD QUOTIENT SIGN POWER SQRT EXP LN LOG LOG10 PI SIN COS TAN ASIN"
          + " ACOS ATAN DEGREES RADIANS CEILING FLOOR LEN LEFT RIGHT MID UPPER LOWER TRIM CLEAN"
          + " CONCAT CONCATENATE TEXTJOIN SUBSTITUTE REPLACE FIND SEARCH EXACT VALUE TEXT REPT DATE"
          + " YEAR MONTH DAY DAYS EDATE EOMONTH WEEKDAY TIME HOUR MINUTE SECOND TODAY NOW ISNUMBER"
          + " ISTEXT ISBLANK ISERROR ISERR ISNA ISLOGICAL N T NA ROWS COLUMNS";
  private static final Set<String> FUNCTIONS = new LinkedHashSet<>(Arrays.asList(EXTRA.split(" ")));

  private SpreadsheetFunctions() {}

  public static List<String> names() {
    List<String> out = new ArrayList<>(FUNCTIONS);
    out.addAll(
        Arrays.asList(
            "SUM", "AVERAGE", "MIN", "MAX", "COUNT", "IF", "IFERROR", "ROUND", "ABS", "AND", "OR"));
    Collections.sort(out);
    return Collections.unmodifiableList(out);
  }

  static boolean supported(String name) {
    return FUNCTIONS.contains(name);
  }

  private static Failure error(String code) {
    return new Failure(code);
  }

  private static boolean blank(Object v) {
    return v == null || "".equals(v);
  }

  private static Object arg(List<Object> a, int i) {
    if (i >= a.size()) throw new IllegalArgumentException("arguments");
    return a.get(i);
  }

  private static double num(List<Object> a, int i) {
    return number(arg(a, i));
  }

  private static int integer(List<Object> a, int i) {
    double n = num(a, i);
    if (!Double.isFinite(n) || Math.abs(n) > Integer.MAX_VALUE)
      throw new IllegalArgumentException();
    return (int) n;
  }

  private static String str(List<Object> a, int i) {
    return text(arg(a, i));
  }

  private static List<?> list(Object a) {
    return a instanceof List ? (List<?>) a : Collections.singletonList(a);
  }

  private static List<Object> flat(List<Object> a) {
    List<Object> out = new ArrayList<>();
    for (Object v : a) out.addAll(list(v));
    return out;
  }

  private static int rows(Object a) {
    return a instanceof Range ? ((Range) a).rows : list(a).size();
  }

  private static int cols(Object a) {
    return a instanceof Range ? ((Range) a).columns : 1;
  }

  private static void arity(List<Object> a, int min, int max) {
    if (a.size() < min || a.size() > max) throw new IllegalArgumentException("arguments");
  }

  static Object apply(String name, List<Object> a, boolean date1904) {
    try {
      Object result = calculate(name, a, date1904);
      if (result instanceof Double && !Double.isFinite((Double) result)) return error("#NUM!");
      if (result instanceof String && ((String) result).length() > 32767) return error("#LIMIT!");
      return result;
    } catch (ArithmeticException ex) {
      return error("#NUM!");
    } catch (Exception ex) {
      return error("#VALUE!");
    }
  }

  private static Object calculate(String n, List<Object> a, boolean mode) {
    // These functions inspect or choose values, including errors.
    if (n.equals("SUMPRODUCT")) {
      arity(a, 1, 255);
      int length = list(a.get(0)).size();
      double sum = 0;
      for (Object v : a)
        if (list(v).size() != length || rows(v) != rows(a.get(0)) || cols(v) != cols(a.get(0)))
          return error("#VALUE!");
      for (int i = 0; i < length; i++) {
        double product = 1;
        for (Object v : a) {
          Object item = list(v).get(i);
          if (item instanceof Failure) return item;
          product *= item instanceof Number ? number(item) : 0;
        }
        sum += product;
      }
      return sum;
    }
    if (Arrays.asList("PMT", "PV", "FV", "NPER").contains(n)) {
      arity(a, 3, 5);
      for (Object v : a) if (v instanceof Failure) return v;
      double r = num(a, 0),
          second = num(a, 1),
          third = num(a, 2),
          fourth = a.size() > 3 ? num(a, 3) : 0,
          type = a.size() > 4 ? num(a, 4) : 0;
      if (r <= -1 || type != 0 && type != 1) return error("#NUM!");
      if (n.equals("NPER")) {
        if (r == 0) return second == 0 ? error("#DIV/0!") : -(third + fourth) / second;
        double amount = second * (1 + r * type),
            ratio = (amount - fourth * r) / (amount + third * r);
        return ratio <= 0 ? error("#NUM!") : Math.log(ratio) / Math.log1p(r);
      }
      double growth = Math.exp(second * Math.log1p(r)),
          factor = r == 0 ? second : Math.expm1(second * Math.log1p(r)) / r;
      factor *= 1 + r * type;
      if (n.equals("PMT"))
        return factor == 0 ? error("#DIV/0!") : -(fourth + third * growth) / factor;
      if (n.equals("PV"))
        return growth == 0 ? error("#DIV/0!") : -(fourth + third * factor) / growth;
      return -(fourth * growth + third * factor);
    }
    if (n.equals("NA")) {
      arity(a, 0, 0);
      return error("#N/A");
    }
    if (n.startsWith("IS")) {
      arity(a, 1, 1);
      Object v = a.get(0);
      switch (n) {
        case "ISNUMBER":
          return v instanceof Number;
        case "ISTEXT":
          return v instanceof String;
        case "ISBLANK":
          return v == null;
        case "ISLOGICAL":
          return v instanceof Boolean;
        case "ISERROR":
          return v instanceof Failure;
        case "ISERR":
          return v instanceof Failure && !v.toString().equals("#N/A");
        case "ISNA":
          return v instanceof Failure && v.toString().equals("#N/A");
        default:
          break;
      }
    }
    if (n.equals("IFNA")) {
      arity(a, 2, 2);
      return a.get(0) instanceof Failure && a.get(0).toString().equals("#N/A")
          ? a.get(1)
          : a.get(0);
    }
    if (n.equals("IFS")) {
      if (a.size() < 2 || a.size() % 2 != 0) throw new IllegalArgumentException();
      for (int i = 0; i < a.size(); i += 2) {
        if (a.get(i) instanceof Failure) return a.get(i);
        if (truth(a.get(i))) return a.get(i + 1);
      }
      return error("#N/A");
    }
    if (n.equals("CHOOSE")) {
      arity(a, 2, 255);
      int i = integer(a, 0);
      return i < 1 || i >= a.size() ? error("#VALUE!") : a.get(i);
    }
    if (n.equals("SWITCH")) {
      arity(a, 3, 255);
      for (int i = 1; i + 1 < a.size(); i += 2) if (equal(a.get(0), a.get(i))) return a.get(i + 1);
      return a.size() % 2 == 0 ? a.get(a.size() - 1) : error("#N/A");
    }
    if (n.equals("INDEX")) {
      arity(a, 2, 3);
      Object range = a.get(0);
      int row = integer(a, 1), col = a.size() == 3 ? integer(a, 2) : 1;
      return row < 1 || col < 1 || row > rows(range) || col > cols(range)
          ? error("#REF!")
          : list(range).get((row - 1) * cols(range) + col - 1);
    }
    if (n.equals("ROWS") || n.equals("COLUMNS")) {
      arity(a, 1, 1);
      return (double) (n.equals("ROWS") ? rows(a.get(0)) : cols(a.get(0)));
    }
    if (n.equals("MATCH") || n.equals("VLOOKUP") || n.equals("HLOOKUP") || n.equals("XLOOKUP"))
      return lookup(n, a);
    if (n.equals("SUMIF") || n.equals("COUNTIF") || n.equals("AVERAGEIF") || n.endsWith("IFS"))
      return conditional(n, a);
    if (n.equals("COUNTA") || n.equals("COUNTBLANK")) {
      arity(a, 1, 255);
      long count = flat(a).stream().filter(v -> n.equals("COUNTA") ? v != null : blank(v)).count();
      return (double) count;
    }
    for (Object v : flat(a)) if (v instanceof Failure) return v;
    if (n.equals("NOT")) {
      arity(a, 1, 1);
      return !truth(a.get(0));
    }
    if (n.equals("XOR")) {
      arity(a, 1, 255);
      boolean result = false;
      for (Object v : flat(a)) result ^= truth(v);
      return result;
    }
    if (Arrays.asList(
            "PRODUCT", "SUMSQ", "MEDIAN", "LARGE", "SMALL", "RANK", "RANK.EQ", "STDEV.S", "STDEV.P",
            "VAR.S", "VAR.P")
        .contains(n)) return statistic(n, a);
    switch (n) {
      case "ROUNDUP":
      case "ROUNDDOWN":
      case "TRUNC":
        {
          arity(a, 1, n.equals("TRUNC") ? 2 : 2);
          if (!n.equals("TRUNC") && a.size() != 2) throw new IllegalArgumentException();
          int digits = a.size() == 1 ? 0 : integer(a, 1);
          if (Math.abs(digits) > 308) return error("#NUM!");
          return BigDecimal.valueOf(num(a, 0))
              .setScale(digits, n.equals("ROUNDUP") ? RoundingMode.UP : RoundingMode.DOWN)
              .doubleValue();
        }
      case "MROUND":
      case "CEILING":
      case "FLOOR":
        {
          arity(a, 2, 2);
          double x = num(a, 0), s = num(a, 1);
          if (s == 0) return n.equals("MROUND") ? 0d : error("#DIV/0!");
          if (x * s < 0) return error("#NUM!");
          double q = x / s;
          return (n.equals("MROUND")
                  ? Math.floor(q + .5)
                  : n.equals("CEILING") ? Math.ceil(q) : Math.floor(q))
              * s;
        }
      case "MOD":
        {
          arity(a, 2, 2);
          double x = num(a, 0), y = num(a, 1);
          return y == 0 ? error("#DIV/0!") : x - y * Math.floor(x / y);
        }
      case "QUOTIENT":
        {
          arity(a, 2, 2);
          double x = num(a, 0), y = num(a, 1);
          return y == 0 ? error("#DIV/0!") : Math.copySign(Math.floor(Math.abs(x / y)), x / y);
        }
      case "POWER":
        arity(a, 2, 2);
        return Math.pow(num(a, 0), num(a, 1));
      case "PI":
        arity(a, 0, 0);
        return Math.PI;
      case "INT":
      case "SIGN":
      case "SQRT":
      case "EXP":
      case "LN":
      case "LOG10":
      case "SIN":
      case "COS":
      case "TAN":
      case "ASIN":
      case "ACOS":
      case "ATAN":
      case "DEGREES":
      case "RADIANS":
        {
          arity(a, 1, 1);
          double x = num(a, 0);
          switch (n) {
            case "INT":
              return Math.floor(x);
            case "SIGN":
              return Math.signum(x);
            case "SQRT":
              return Math.sqrt(x);
            case "EXP":
              return Math.exp(x);
            case "LN":
              return Math.log(x);
            case "LOG10":
              return Math.log10(x);
            case "SIN":
              return Math.sin(x);
            case "COS":
              return Math.cos(x);
            case "TAN":
              return Math.tan(x);
            case "ASIN":
              return Math.asin(x);
            case "ACOS":
              return Math.acos(x);
            case "ATAN":
              return Math.atan(x);
            case "DEGREES":
              return Math.toDegrees(x);
            default:
              return Math.toRadians(x);
          }
        }
      case "LOG":
        arity(a, 1, 2);
        double base = a.size() == 1 ? 10 : num(a, 1);
        return base <= 0 || base == 1 ? error("#NUM!") : Math.log(num(a, 0)) / Math.log(base);
      case "LEN":
        arity(a, 1, 1);
        return (double) str(a, 0).length();
      case "LEFT":
      case "RIGHT":
        {
          arity(a, 1, 2);
          String s = str(a, 0);
          int count = a.size() == 1 ? 1 : integer(a, 1);
          if (count < 0) return error("#VALUE!");
          count = Math.min(count, s.length());
          return n.equals("LEFT") ? s.substring(0, count) : s.substring(s.length() - count);
        }
      case "MID":
        {
          arity(a, 3, 3);
          String s = str(a, 0);
          int start = integer(a, 1) - 1, count = integer(a, 2);
          if (start < 0 || count < 0) return error("#VALUE!");
          return start >= s.length()
              ? ""
              : s.substring(start, (int) Math.min(s.length(), (long) start + count));
        }
      case "UPPER":
        arity(a, 1, 1);
        return str(a, 0).toUpperCase(Locale.ROOT);
      case "LOWER":
        arity(a, 1, 1);
        return str(a, 0).toLowerCase(Locale.ROOT);
      case "TRIM":
        arity(a, 1, 1);
        return str(a, 0).replaceAll("^ +| +$", "").replaceAll(" +", " ");
      case "CLEAN":
        arity(a, 1, 1);
        return str(a, 0).replaceAll("[\\x00-\\x1F]", "");
      case "CONCAT":
      case "CONCATENATE":
        {
          arity(a, 1, 255);
          StringBuilder b = new StringBuilder();
          for (Object v : flat(a)) {
            b.append(text(v));
            if (b.length() > 32767) return error("#LIMIT!");
          }
          return b.toString();
        }
      case "TEXTJOIN":
        {
          arity(a, 3, 255);
          StringJoiner j = new StringJoiner(str(a, 0));
          boolean ignore = truth(a.get(1));
          int total = 0;
          for (Object v : flat(a.subList(2, a.size()))) {
            if (ignore && blank(v)) continue;
            total += text(v).length() + str(a, 0).length();
            if (total > 65536) return error("#LIMIT!");
            j.add(text(v));
          }
          return j.toString();
        }
      case "SUBSTITUTE":
        {
          arity(a, 3, 4);
          String s = str(a, 0), old = str(a, 1), replacement = str(a, 2);
          if (old.isEmpty()) return s;
          if (a.size() == 3) return s.replace(old, replacement);
          int occurrence = integer(a, 3);
          if (occurrence < 1) return error("#VALUE!");
          int start = 0, pos = -1;
          for (int i = 0; i < occurrence; i++) {
            pos = s.indexOf(old, start);
            if (pos < 0) return s;
            start = pos + old.length();
          }
          return s.substring(0, pos) + replacement + s.substring(start);
        }
      case "REPLACE":
        {
          arity(a, 4, 4);
          String s = str(a, 0);
          int start = integer(a, 1) - 1, count = integer(a, 2);
          if (start < 0 || count < 0) return error("#VALUE!");
          start = Math.min(start, s.length());
          return s.substring(0, start)
              + str(a, 3)
              + s.substring((int) Math.min(s.length(), (long) start + count));
        }
      case "FIND":
      case "SEARCH":
        {
          arity(a, 2, 3);
          String needle = str(a, 0), s = str(a, 1);
          int start = a.size() == 3 ? integer(a, 2) - 1 : 0;
          if (start < 0 || start > s.length()) return error("#VALUE!");
          int found = n.equals("FIND") ? s.indexOf(needle, start) : globSearch(s, needle, start);
          return found < 0 ? error("#VALUE!") : (double) (found + 1);
        }
      case "EXACT":
        arity(a, 2, 2);
        return str(a, 0).equals(str(a, 1));
      case "VALUE":
        arity(a, 1, 1);
        return Double.parseDouble(str(a, 0).trim().replace(",", ""));
      case "TEXT":
        {
          arity(a, 2, 2);
          String f = str(a, 1);
          if (f.equals("yyyy-mm-dd")) return date(num(a, 0), mode).toString();
          if (!f.matches("[#,0.]+%?")) return error("#UNSUPPORTED(TEXT_FORMAT)");
          return new java.text.DecimalFormat(
                  f, java.text.DecimalFormatSymbols.getInstance(Locale.US))
              .format(num(a, 0));
        }
      case "REPT":
        {
          arity(a, 2, 2);
          String s = str(a, 0);
          int count = integer(a, 1);
          if (count < 0) return error("#VALUE!");
          if ((long) s.length() * count > 32767) return error("#LIMIT!");
          StringBuilder b = new StringBuilder();
          for (int i = 0; i < count; i++) b.append(s);
          return b.toString();
        }
      case "N":
        arity(a, 1, 1);
        Object v = a.get(0);
        return v instanceof Number || v instanceof Boolean ? number(v) : 0d;
      case "T":
        arity(a, 1, 1);
        return a.get(0) instanceof String ? a.get(0) : "";
      case "DATE":
        {
          arity(a, 3, 3);
          int y = integer(a, 0);
          if (y >= 0 && y < 1900) y += 1900;
          if (y < 0 || y > 9999) return error("#NUM!");
          int month = integer(a, 1), day = integer(a, 2);
          if (y == 1900 && month == 2 && day == 29 && !mode) return 60d;
          return serial(
              LocalDate.of(y, 1, 1).plusMonths((long) month - 1).plusDays((long) day - 1), mode);
        }
      case "TODAY":
        arity(a, 0, 0);
        return serial(LocalDate.now(), mode);
      case "NOW":
        arity(a, 0, 0);
        LocalDateTime now = LocalDateTime.now();
        return serial(now.toLocalDate(), mode) + now.toLocalTime().toSecondOfDay() / 86400d;
      case "YEAR":
      case "MONTH":
      case "DAY":
      case "WEEKDAY":
        {
          arity(a, 1, n.equals("WEEKDAY") ? 2 : 1);
          double serial = num(a, 0);
          if (!mode && (long) Math.floor(serial) == 60 && !n.equals("WEEKDAY"))
            return n.equals("YEAR") ? 1900d : n.equals("MONTH") ? 2d : 29d;
          LocalDate d = date(serial, mode);
          switch (n) {
            case "YEAR":
              return (double) d.getYear();
            case "MONTH":
              return (double) d.getMonthValue();
            case "DAY":
              return (double) d.getDayOfMonth();
            default:
              int type = a.size() == 1 ? 1 : integer(a, 1), day = d.getDayOfWeek().getValue();
              return type == 1
                  ? (double) (day % 7 + 1)
                  : type == 2
                      ? (double) day
                      : type == 3 ? (double) (day - 1) : error("#UNSUPPORTED(WEEKDAY_MODE)");
          }
        }
      case "DAYS":
        arity(a, 2, 2);
        return Math.floor(num(a, 0)) - Math.floor(num(a, 1));
      case "EDATE":
      case "EOMONTH":
        {
          arity(a, 2, 2);
          LocalDate d = date(num(a, 0), mode).plusMonths(integer(a, 1));
          if (n.equals("EOMONTH")) d = d.withDayOfMonth(d.lengthOfMonth());
          return serial(d, mode);
        }
      case "TIME":
        {
          arity(a, 3, 3);
          double seconds = integer(a, 0) * 3600d + integer(a, 1) * 60d + integer(a, 2);
          return seconds < 0 ? error("#NUM!") : (seconds % 86400) / 86400d;
        }
      case "HOUR":
      case "MINUTE":
      case "SECOND":
        {
          arity(a, 1, 1);
          double serial = num(a, 0);
          if (serial < 0) return error("#NUM!");
          int seconds = (int) Math.round((serial - Math.floor(serial)) * 86400) % 86400;
          return (double)
              (n.equals("HOUR")
                  ? seconds / 3600
                  : n.equals("MINUTE") ? seconds / 60 % 60 : seconds % 60);
        }
      default:
        return error("#UNSUPPORTED(" + n + ")");
    }
  }

  private static Object statistic(String n, List<Object> a) {
    arity(a, 1, 255);
    List<Object> input =
        (n.equals("LARGE") || n.equals("SMALL"))
            ? Collections.singletonList(a.get(0))
            : (n.startsWith("RANK")) ? Collections.singletonList(arg(a, 1)) : a;
    List<Double> values = new ArrayList<>();
    for (Object v : flat(input)) if (v instanceof Number) values.add(number(v));
    Collections.sort(values);
    int size = values.size();
    if (n.equals("PRODUCT") || n.equals("SUMSQ")) {
      double r = n.equals("PRODUCT") ? (size == 0 ? 0 : 1) : 0;
      for (double x : values) r = n.equals("PRODUCT") ? r * x : r + x * x;
      return r;
    }
    if (size == 0) return error("#NUM!");
    if (n.equals("MEDIAN"))
      return size % 2 == 1
          ? values.get(size / 2)
          : (values.get(size / 2 - 1) + values.get(size / 2)) / 2;
    if (n.equals("LARGE") || n.equals("SMALL")) {
      arity(a, 2, 2);
      int k = integer(a, 1);
      return k < 1 || k > size ? error("#NUM!") : values.get(n.equals("LARGE") ? size - k : k - 1);
    }
    if (n.startsWith("RANK")) {
      arity(a, 2, 3);
      double x = num(a, 0);
      if (!values.contains(x)) return error("#N/A");
      boolean ascending = a.size() == 3 && num(a, 2) != 0;
      long count = values.stream().filter(v -> ascending ? v < x : v > x).count();
      return (double) count + 1;
    }
    int denominator = size - (n.endsWith(".S") ? 1 : 0);
    if (denominator <= 0) return error("#DIV/0!");
    double mean = 0;
    for (double x : values) mean += x;
    mean /= size;
    double variance = 0;
    for (double x : values) variance += (x - mean) * (x - mean);
    variance /= denominator;
    return n.startsWith("STDEV") ? Math.sqrt(variance) : variance;
  }

  private static Object conditional(String n, List<Object> a) {
    boolean multi = n.endsWith("IFS"),
        count = n.startsWith("COUNT"),
        average = n.startsWith("AVERAGE");
    List<?> output;
    int start;
    if (multi) {
      start = count ? 0 : 1;
      if (a.size() < start + 2 || (a.size() - start) % 2 != 0) throw new IllegalArgumentException();
      output = list(a.get(0));
    } else {
      arity(a, 2, count ? 2 : 3);
      start = 0;
      output = list(a.size() == 3 ? a.get(2) : a.get(0));
    }
    int length = list(a.get(start)).size();
    if (output.size() != length) return error("#VALUE!");
    double sum = 0;
    int matches = 0;
    for (int i = 0; i < length; i++) {
      boolean yes = true;
      for (int p = start; p < (multi ? a.size() : 2); p += 2) {
        List<?> range = list(a.get(p));
        if (range.size() != length
            || rows(a.get(p)) != rows(a.get(start))
            || cols(a.get(p)) != cols(a.get(start))) return error("#VALUE!");
        yes &= criteria(range.get(i), a.get(p + 1));
      }
      if (!yes) continue;
      Object v = output.get(i);
      if (count) {
        matches++;
        continue;
      }
      if (v instanceof Failure) return v;
      if (v instanceof Number) {
        sum += number(v);
        matches++;
      }
    }
    return count
        ? (double) matches
        : average ? (matches == 0 ? error("#DIV/0!") : sum / matches) : sum;
  }

  private static Object lookup(String n, List<Object> a) {
    arity(a, n.equals("MATCH") ? 2 : 3, n.equals("XLOOKUP") ? 6 : n.equals("MATCH") ? 3 : 4);
    Object needle = a.get(0), range = a.get(1);
    List<?> values = list(range);
    int mode;
    if (n.equals("XLOOKUP")) {
      Object result = a.get(2);
      if (values.size() != list(result).size() || (rows(range) > 1 && cols(range) > 1))
        return error("#VALUE!");
      mode = a.size() > 4 ? integer(a, 4) : 0;
      int search = a.size() > 5 ? integer(a, 5) : 1;
      if ((mode != 0 && mode != 2) || (search != 1 && search != -1))
        return error("#UNSUPPORTED(XLOOKUP_MODE)");
      for (int offset = 0; offset < values.size(); offset++) {
        int i = search == 1 ? offset : values.size() - offset - 1;
        if (mode == 2 ? criteria(values.get(i), needle) : equal(values.get(i), needle))
          return list(result).get(i);
      }
      return a.size() > 3 ? a.get(3) : error("#N/A");
    }
    if (n.equals("MATCH")) {
      mode = a.size() == 3 ? integer(a, 2) : 1;
      if (mode < -1 || mode > 1) return error("#VALUE!");
      int selected = -1;
      for (int i = 0; i < values.size(); i++) {
        Object v = values.get(i);
        if (mode == 0 ? criteria(v, needle) : equal(v, needle)) return (double) i + 1;
        int c = compare(v, needle);
        if (mode == 1 && c <= 0 || mode == -1 && c >= 0) selected = i;
      }
      return selected < 0 ? error("#N/A") : (double) selected + 1;
    }
    boolean vertical = n.equals("VLOOKUP");
    int columns = cols(range), rows = rows(range), target = integer(a, 2);
    if (target < 1 || target > (vertical ? columns : rows)) return error("#REF!");
    boolean approx = a.size() < 4 || truth(a.get(3));
    int selected = -1;
    for (int i = 0; i < (vertical ? rows : columns); i++) {
      Object v = values.get(vertical ? i * columns : i);
      if (approx ? equal(v, needle) : criteria(v, needle)) {
        selected = i;
        break;
      }
      if (approx && compare(v, needle) <= 0) selected = i;
    }
    return selected < 0
        ? error("#N/A")
        : values.get(
            vertical ? selected * columns + target - 1 : (target - 1) * columns + selected);
  }

  private static boolean equal(Object a, Object b) {
    if (a instanceof Failure || b instanceof Failure) return false;
    if (a == null) a = 0d;
    if (b == null) b = 0d;
    if (a instanceof Number && b instanceof Number)
      return Double.compare(number(a), number(b)) == 0;
    if (a instanceof String && b instanceof String) return text(a).equalsIgnoreCase(text(b));
    if (a instanceof Boolean && b instanceof Boolean) return a.equals(b);
    return false;
  }

  private static int compare(Object a, Object b) {
    if (a instanceof Number && b instanceof Number) return Double.compare(number(a), number(b));
    return text(a).compareToIgnoreCase(text(b));
  }

  private static boolean criteria(Object value, Object raw) {
    if (value instanceof Failure || raw instanceof Failure) return false;
    if (!(raw instanceof String)) return equal(value, raw);
    String s = (String) raw, op = "=";
    for (String prefix : Arrays.asList("<=", ">=", "<>", "<", ">", "="))
      if (s.startsWith(prefix)) {
        op = prefix;
        s = s.substring(prefix.length());
        break;
      }
    int c;
    try {
      c = Double.compare(number(value), Double.parseDouble(s));
    } catch (Exception e) {
      if (op.equals("=") || op.equals("<>")) {
        boolean match = glob(text(value), s, 0, true);
        return op.equals("=") ? match : !match;
      }
      c = text(value).compareToIgnoreCase(s);
    }
    return op.equals("=")
        ? c == 0
        : op.equals("<>")
            ? c != 0
            : op.equals("<") ? c < 0 : op.equals(">") ? c > 0 : op.equals("<=") ? c <= 0 : c >= 0;
  }

  private static int globSearch(String text, String pattern, int start) {
    if (pattern.length() > 255) throw new IllegalArgumentException("wildcard length");
    for (int i = start; i <= text.length(); i++) if (glob(text, pattern, i, false)) return i;
    return -1;
  }

  // Iterative glob matching avoids regex backtracking on document-controlled criteria.
  private static boolean glob(String text, String pattern, int offset, boolean entire) {
    if (pattern.length() > 255) throw new IllegalArgumentException("wildcard length");
    int t = offset, p = 0, star = -1, retry = 0, steps = 0;
    while (t < text.length()) {
      if (++steps > 100000) throw new IllegalArgumentException("wildcard budget");
      if (!entire && p == pattern.length()) return true;
      if (p < pattern.length()) {
        char c = pattern.charAt(p);
        boolean escape = c == '~' && p + 1 < pattern.length();
        int advance = escape ? 2 : 1;
        if (escape) c = pattern.charAt(p + 1);
        if (!escape && c == '*') {
          star = p++;
          retry = t;
          continue;
        }
        if (!escape && c == '?'
            || Character.toLowerCase(c) == Character.toLowerCase(text.charAt(t))) {
          t++;
          p += advance;
          continue;
        }
      }
      if (star >= 0) {
        p = star + 1;
        t = ++retry;
      } else return false;
    }
    while (p < pattern.length() && pattern.charAt(p) == '*') p++;
    return p == pattern.length();
  }

  private static String wildcard(String s) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '~' && i + 1 < s.length()) b.append(Pattern.quote("" + s.charAt(++i)));
      else if (c == '*') b.append(".*");
      else if (c == '?') b.append('.');
      else b.append(Pattern.quote("" + c));
    }
    return b.toString();
  }

  private static LocalDate date(double n, boolean mode) {
    long days = (long) Math.floor(n);
    if (!mode && days >= 60) days--;
    return LocalDate.of(mode ? 1904 : 1899, mode ? 1 : 12, mode ? 1 : 31).plusDays(days);
  }

  private static double serial(LocalDate d, boolean mode) {
    long days =
        ChronoUnit.DAYS.between(LocalDate.of(mode ? 1904 : 1899, mode ? 1 : 12, mode ? 1 : 31), d);
    return days + (!mode && !d.isBefore(LocalDate.of(1900, 3, 1)) ? 1 : 0);
  }
}
