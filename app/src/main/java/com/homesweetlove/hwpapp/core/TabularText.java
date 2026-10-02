package com.homesweetlove.hwpapp.core;

import java.util.*;

/** Tab-separated clipboard data, including quoted embedded newlines and quotes. */
public final class TabularText {
  private TabularText() {}

  public static List<List<String>> parse(String text) {
    if (text.length() > 1048576) throw new IllegalArgumentException("붙여넣기 내용은 1MB까지 지원합니다.");
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false, closed = false;
    int cells = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            value.append('"');
            i++;
          } else {
            quoted = false;
            closed = true;
          }
        } else value.append(c);
        continue;
      }
      if (c == '"' && value.length() == 0 && !closed) {
        quoted = true;
        continue;
      }
      if (c == '\t' || c == '\r' || c == '\n') {
        row.add(value.toString());
        if (++cells > 10000) throw new IllegalArgumentException("10,000셀까지 붙여넣을 수 있습니다.");
        value.setLength(0);
        closed = false;
        if (c != '\t') {
          rows.add(row);
          row = new ArrayList<>();
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        }
        continue;
      }
      if (closed) throw new IllegalArgumentException("따옴표 뒤에 구분자가 필요합니다.");
      value.append(c);
    }
    if (quoted) throw new IllegalArgumentException("닫히지 않은 따옴표가 있습니다.");
    if (value.length() > 0 || !row.isEmpty() || rows.isEmpty()) {
      row.add(value.toString());
      if (++cells > 10000) throw new IllegalArgumentException("10,000셀까지 붙여넣을 수 있습니다.");
      rows.add(row);
    }
    return rows;
  }
}
