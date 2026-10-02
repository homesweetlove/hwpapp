package com.homesweetlove.hwpapp.core;

import java.util.*;

/** Bounded spreadsheet expression interpreter; unsupported functions return explicit errors. */
public final class FormulaEngine {
  public interface Resolver {
    Object cell(String sheet, String address);

    default boolean date1904() {
      return false;
    }
  }

  static final class Range extends ArrayList<Object> {
    final int rows, columns;

    Range(int rows, int columns) {
      this.rows = rows;
      this.columns = columns;
    }
  }

  public static final class Failure {
    public final String code;

    public Failure(String code) {
      this.code = code;
    }

    public String toString() {
      return code;
    }
  }

  private final String source;
  private final Resolver resolver;
  private int position, rangeCells, depth, nested;

  private FormulaEngine(String text, Resolver resolver) {
    source = text;
    this.resolver = resolver;
  }

  public static Object evaluate(String text, Resolver resolver) {
    try {
      if (text.length() > 8192) return new Failure("#LIMIT!");
      FormulaEngine engine =
          new FormulaEngine(text.startsWith("=") ? text.substring(1) : text, resolver);
      Object value = engine.compare();
      engine.space();
      if (value instanceof Number && !Double.isFinite(((Number) value).doubleValue()))
        return new Failure("#NUM!");
      return engine.position == engine.source.length() && !(value instanceof List)
          ? value
          : new Failure("#SYNTAX!");
    } catch (ArithmeticException e) {
      return new Failure("#DIV/0!");
    } catch (Exception e) {
      return new Failure("#VALUE!");
    }
  }

  private void space() {
    while (position < source.length() && Character.isWhitespace(source.charAt(position)))
      position++;
  }

  private boolean consume(String s) {
    space();
    if (source.startsWith(s, position)) {
      position += s.length();
      return true;
    }
    return false;
  }

  private Object compare() {
    if (++depth > 64) throw new IllegalArgumentException("depth");
    Object value = concat();
    for (String op : Arrays.asList("<=", ">=", "<>", "=", "<", ">"))
      if (consume(op)) {
        Object other = concat();
        if (value instanceof Failure) {
          depth--;
          return value;
        }
        if (other instanceof Failure) {
          depth--;
          return other;
        }
        int c;
        if (value == null) value = other instanceof String ? "" : 0d;
        if (other == null) other = value instanceof String ? "" : 0d;
        if (value instanceof Number && other instanceof Number)
          c = Double.compare(number(value), number(other));
        else if (value instanceof String && other instanceof String)
          c = text(value).compareToIgnoreCase(text(other));
        else if (value instanceof Boolean && other instanceof Boolean)
          c = Boolean.compare((Boolean) value, (Boolean) other);
        else
          c =
              Integer.compare(
                  value instanceof Boolean ? 2 : value instanceof String ? 1 : 0,
                  other instanceof Boolean ? 2 : other instanceof String ? 1 : 0);
        value =
            op.equals("=")
                ? c == 0
                : op.equals("<>")
                    ? c != 0
                    : op.equals("<")
                        ? c < 0
                        : op.equals(">") ? c > 0 : op.equals("<=") ? c <= 0 : c >= 0;
        break;
      }
    depth--;
    return value;
  }

  private Object concat() {
    Object a = sum();
    while (consume("&")) {
      Object b = sum();
      a =
          a instanceof Failure
              ? a
              : b instanceof Failure
                  ? b
                  : a instanceof List || b instanceof List
                      ? new Failure("#UNSUPPORTED(ARRAY)")
                      : text(a) + text(b);
    }
    return a;
  }

  private Object sum() {
    Object value = product();
    while (true) {
      if (consume("+")) value = arithmetic(value, product(), '+');
      else if (consume("-")) value = arithmetic(value, product(), '-');
      else return value;
    }
  }

  private Object product() {
    Object value = power();
    while (true) {
      if (consume("*")) value = arithmetic(value, power(), '*');
      else if (consume("/")) value = arithmetic(value, power(), '/');
      else return value;
    }
  }

  private Object power() {
    Object a = percent();
    while (consume("^")) a = arithmetic(a, percent(), '^');
    return a;
  }

  private Object percent() {
    Object a = unary();
    while (consume("%")) a = arithmetic(a, 100d, '/');
    return a;
  }

  private Object unary() {
    if (++nested > 128) throw new IllegalArgumentException("nesting");
    Object result;
    if (consume("+")) result = unary();
    else if (consume("-")) result = arithmetic(0d, unary(), '-');
    else result = atom();
    nested--;
    return result;
  }

  private Object atom() {
    space();
    if (position >= source.length()) throw new IllegalArgumentException("missing expression");
    if (source.charAt(position) == '#') {
      for (String code :
          Arrays.asList("#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A", "#NULL!"))
        if (consume(code)) return new Failure(code);
      throw new IllegalArgumentException("error literal");
    }
    if (consume("(")) {
      Object value = compare();
      if (!consume(")")) throw new IllegalArgumentException("parenthesis");
      return value;
    }
    if (consume("\"")) {
      StringBuilder out = new StringBuilder();
      while (position < source.length()) {
        char c = source.charAt(position++);
        if (c == '"') {
          if (position < source.length() && source.charAt(position) == '"') {
            position++;
            out.append('"');
          } else return out.toString();
        } else out.append(c);
      }
      throw new IllegalArgumentException("string");
    }
    if (Character.isDigit(source.charAt(position)) || source.charAt(position) == '.') {
      int start = position;
      while (position < source.length()
          && (Character.isDigit(source.charAt(position)) || source.charAt(position) == '.'))
        position++;
      if (position < source.length()
          && (source.charAt(position) == 'E' || source.charAt(position) == 'e')) {
        position++;
        if (position < source.length()
            && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
        while (position < source.length() && Character.isDigit(source.charAt(position))) position++;
      }
      return Double.parseDouble(source.substring(start, position));
    }
    String token;
    boolean quoted = consume("'");
    if (quoted) {
      StringBuilder out = new StringBuilder();
      boolean done = false;
      while (position < source.length()) {
        char c = source.charAt(position++);
        if (c == '\'') {
          if (position < source.length() && source.charAt(position) == '\'') {
            position++;
            out.append('\'');
          } else {
            done = true;
            break;
          }
        } else out.append(c);
      }
      if (!done) throw new IllegalArgumentException("sheet");
      token = out.toString();
    } else {
      int start = position;
      while (position < source.length()
          && (Character.isLetterOrDigit(source.charAt(position))
              || "_$ .".indexOf(source.charAt(position)) >= 0 && source.charAt(position) != ' '))
        position++;
      if (start == position) throw new IllegalArgumentException("token");
      token = source.substring(start, position);
    }
    if (!quoted && consume("(")) {
      List<Object> args = new ArrayList<>();
      if (!consume(")")) {
        do {
          args.add(compare());
        } while (consume(",") || consume(";"));
        if (!consume(")")) throw new IllegalArgumentException("function");
      }
      return function(
          token
              .toUpperCase(Locale.ROOT)
              .replaceFirst("^_XLFN\\.", "")
              .replaceFirst("^_XLWS\\.", ""),
          args);
    }
    String sheet = null;
    if (consume("!")) {
      sheet = token;
      int start = position;
      while (position < source.length()
          && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position) == '$'))
        position++;
      token = source.substring(start, position);
    }
    if (sheet == null && token.equalsIgnoreCase("TRUE")) return true;
    if (sheet == null && token.equalsIgnoreCase("FALSE")) return false;
    int[] from = Spreadsheet.coordinate(token);
    if (consume(":")) {
      int start = position;
      while (position < source.length()
          && (Character.isLetterOrDigit(source.charAt(position)) || source.charAt(position) == '$'))
        position++;
      int[] to = Spreadsheet.coordinate(source.substring(start, position));
      long count = (long) (Math.abs(from[0] - to[0]) + 1) * (Math.abs(from[1] - to[1]) + 1);
      if (count + rangeCells > 10000) return new Failure("#LIMIT!");
      rangeCells += count;
      List<Object> values = new Range(Math.abs(from[1] - to[1]) + 1, Math.abs(from[0] - to[0]) + 1);
      for (int y = Math.min(from[1], to[1]); y <= Math.max(from[1], to[1]); y++)
        for (int x = Math.min(from[0], to[0]); x <= Math.max(from[0], to[0]); x++)
          values.add(resolver.cell(sheet, Spreadsheet.address(x, y)));
      return values;
    }
    return resolver.cell(sheet, Spreadsheet.address(from[0], from[1]));
  }

  private static Object arithmetic(Object a, Object b, char op) {
    if (a instanceof Failure) return a;
    if (b instanceof Failure) return b;
    try {
      double x = number(a), y = number(b), result;
      if (op == '/' && y == 0) return new Failure("#DIV/0!");
      result =
          op == '+'
              ? x + y
              : op == '-' ? x - y : op == '*' ? x * y : op == '/' ? x / y : Math.pow(x, y);
      return Double.isFinite(result) ? result : new Failure("#NUM!");
    } catch (Exception e) {
      return new Failure("#VALUE!");
    }
  }

  private Object function(String name, List<Object> args) {
    try {
      if (args.size() > 255) return new Failure("#LIMIT!");
      if (name.equals("IF") && args.size() >= 2 && args.size() <= 3) {
        Object condition = args.get(0);
        if (condition instanceof Failure) return condition;
        return truth(condition) ? args.get(1) : args.size() == 3 ? args.get(2) : false;
      }
      if (name.equals("IFERROR") && args.size() == 2)
        return args.get(0) instanceof Failure ? args.get(1) : args.get(0);
      if (name.equals("ABS") && args.size() == 1)
        return args.get(0) instanceof Failure ? args.get(0) : Math.abs(number(args.get(0)));
      if (name.equals("ROUND") && args.size() == 2) {
        for (Object v : args) if (v instanceof Failure) return v;
        double digits = number(args.get(1));
        if (!Double.isFinite(digits) || Math.abs(digits) > 308) return new Failure("#NUM!");
        return java.math.BigDecimal.valueOf(number(args.get(0)))
            .setScale((int) digits, java.math.RoundingMode.HALF_UP)
            .doubleValue();
      }
      if (name.equals("AND") || name.equals("OR")) {
        boolean result = name.equals("AND");
        for (Object arg : flatten(args)) {
          if (arg instanceof Failure) return arg;
          result = name.equals("AND") ? result && truth(arg) : result || truth(arg);
        }
        return result;
      }
      if (SpreadsheetFunctions.supported(name))
        return SpreadsheetFunctions.apply(name, args, resolver.date1904());
      List<Double> numbers = new ArrayList<>();
      for (Object arg : args) {
        if (arg instanceof List) {
          for (Object item : (List<?>) arg) {
            if (item instanceof Failure) return item;
            if (item instanceof Number) numbers.add(number(item));
          }
        } else {
          if (arg instanceof Failure) return arg;
          if (arg instanceof Number || arg instanceof Boolean) numbers.add(number(arg));
          else if (arg instanceof String && !((String) arg).isEmpty()) {
            try {
              numbers.add(number(arg));
            } catch (Exception invalid) {
              if (!name.equals("COUNT")) return new Failure("#VALUE!");
            }
          }
        }
      }
      if (name.equals("COUNT")) return (double) numbers.size();
      if (!Arrays.asList("SUM", "AVERAGE", "MIN", "MAX").contains(name))
        return new Failure("#UNSUPPORTED(" + name + ")");
      if (numbers.isEmpty()) return name.equals("AVERAGE") ? new Failure("#DIV/0!") : 0d;
      double result = 0;
      for (double n : numbers) result += n;
      return name.equals("SUM")
          ? result
          : name.equals("AVERAGE")
              ? result / numbers.size()
              : name.equals("MIN") ? Collections.min(numbers) : Collections.max(numbers);
    } catch (Exception e) {
      return new Failure("#VALUE!");
    }
  }

  private static List<Object> flatten(List<Object> list) {
    List<Object> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof List) out.addAll((List<?>) item);
      else out.add(item);
    }
    return out;
  }

  static boolean truth(Object value) {
    return value instanceof Boolean ? (Boolean) value : number(value) != 0;
  }

  static double number(Object value) {
    if (value instanceof Failure) throw new IllegalArgumentException(value.toString());
    if (value == null || "".equals(value)) return 0;
    if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
    return value instanceof Number
        ? ((Number) value).doubleValue()
        : Double.parseDouble(value.toString());
  }

  public static String text(Object value) {
    if (value == null) return "";
    if (value instanceof Double)
      return java.math.BigDecimal.valueOf((Double) value).stripTrailingZeros().toPlainString();
    return value instanceof Boolean ? ((Boolean) value ? "TRUE" : "FALSE") : value.toString();
  }
}
