package com.homesweetlove.hwpapp.core;

public final class DocumentStyle {
  public String font = "sans-serif", alignment = "left", numberFormat = "general";
  public float size = 11;
  public int color = 0xff000000;
  public boolean bold;

  public DocumentStyle copy() {
    DocumentStyle s = new DocumentStyle();
    s.font = font;
    s.alignment = alignment;
    s.numberFormat = numberFormat;
    s.size = size;
    s.color = color;
    s.bold = bold;
    return s;
  }

  public void validate() {
    if (!Float.isFinite(size) || size < 6 || size > 96)
      throw new IllegalArgumentException("글자 크기는 6–96pt입니다.");
    if (font.trim().isEmpty() || font.length() > 80)
      throw new IllegalArgumentException("글꼴 이름을 확인하세요.");
    if (!java.util.Arrays.asList("left", "center", "right").contains(alignment))
      throw new IllegalArgumentException("정렬을 확인하세요.");
  }
}
