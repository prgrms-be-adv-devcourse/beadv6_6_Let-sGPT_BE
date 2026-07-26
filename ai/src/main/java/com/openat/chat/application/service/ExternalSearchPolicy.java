package com.openat.chat.application.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ExternalSearchPolicy {

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE =
      Pattern.compile("(?<![0-9])01[016789][ -]?[0-9]{3,4}[ -]?[0-9]{4}(?![0-9])");
  private static final Pattern UUID =
      Pattern.compile(
          "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])");
  private static final Pattern ORDER_NUMBER =
      Pattern.compile("(?i)(?<![A-Za-z0-9-])ORD-[A-Za-z0-9-]{1,26}(?![A-Za-z0-9-])");

  public void validate(String query) {
    if (query == null || query.isBlank() || query.length() > 200) {
      throw new IllegalArgumentException("웹 검색어 길이가 올바르지 않아요.");
    }
    if (EMAIL.matcher(query).find()
        || PHONE.matcher(query).find()
        || UUID.matcher(query).find()
        || ORDER_NUMBER.matcher(query).find()) {
      throw new IllegalArgumentException("식별 정보가 포함된 내용은 외부 웹 검색으로 보내지 않아요.");
    }
  }
}
