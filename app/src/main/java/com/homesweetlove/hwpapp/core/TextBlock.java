package com.homesweetlove.hwpapp.core;

public final class TextBlock {
  public final String label;
  public final boolean editable;
  public String text;

  public interface Writer {
    void write(String value) throws Exception;
  }

  private final Writer writer;

  public TextBlock(String label, String text, boolean editable, Writer writer) {
    this.label = label;
    this.text = text;
    this.editable = editable;
    this.writer = writer;
  }

  public void set(String value) throws Exception {
    if (!editable) throw new IllegalStateException("이 부분은 읽기 전용입니다.");
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
      throw new IllegalArgumentException("문단 내부에는 줄바꿈을 넣을 수 없습니다.");
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < 32) throw new IllegalArgumentException("제어 문자는 입력할 수 없습니다.");
    writer.write(value);
    text = value;
  }
}
