package com.openat.chat.domain.planning;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

public final class PlanningDateTimeValidator {

  public static final ZoneId SERVER_TIME_ZONE = ZoneId.of("Asia/Seoul");

  private static final DateTimeFormatter FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuu-MM-dd HH:mm:ss")
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);

  private PlanningDateTimeValidator() {}

  public static ZonedDateTime parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("날짜와 시각이 필요해요.");
    }
    try {
      return LocalDateTime.parse(value, FORMATTER).atZone(SERVER_TIME_ZONE);
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("날짜는 yyyy-MM-dd HH:mm:ss 형식이어야 해요.", exception);
    }
  }
}
