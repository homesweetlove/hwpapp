package com.homesweetlove.hwpapp.core;

import java.util.*;

public final class PageRanges {
  public static List<Integer> parse(String text, int count, boolean permutation) {
    if (text.length() > 4096) throw new IllegalArgumentException("페이지 목록이 너무 깁니다.");
    List<Integer> result = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    for (String token : text.split(",")) {
      String[] ends = token.trim().split("-", -1);
      if (ends.length < 1 || ends.length > 2)
        throw new IllegalArgumentException("1-3,5 형식으로 입력하세요.");
      int from = Integer.parseInt(ends[0].trim()),
          to = ends.length == 1 ? from : Integer.parseInt(ends[1].trim());
      if (from < 1 || to < 1 || from > count || to > count)
        throw new IllegalArgumentException("페이지 범위를 벗어났습니다.");
      int step = to >= from ? 1 : -1;
      for (int n = from; ; n += step) {
        if (!seen.add(n - 1)) throw new IllegalArgumentException("중복 페이지가 있습니다.");
        result.add(n - 1);
        if (n == to) break;
      }
    }
    if (result.isEmpty() || permutation && result.size() != count)
      throw new IllegalArgumentException("모든 페이지를 한 번씩 입력하세요.");
    return result;
  }
}
