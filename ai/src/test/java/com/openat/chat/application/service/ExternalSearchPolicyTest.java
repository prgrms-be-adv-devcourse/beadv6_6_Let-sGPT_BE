package com.openat.chat.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("외부 웹 검색 전송 정책")
class ExternalSearchPolicyTest {

  private final ExternalSearchPolicy policy = new ExternalSearchPolicy();

  @Test
  @DisplayName("공개 최신 정보 검색어는 허용한다")
  void validate_publicQuery_allows() {
    assertThatCode(() -> policy.validate("오늘 비트코인 원화 가격")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("주문번호·이메일·내부 UUID는 외부 공급자에 보내지 않는다")
  void validate_identifiers_rejects() {
    assertThatThrownBy(() -> policy.validate("ORD-AI-SAGA-0001 검색"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.validate("person@example.com 검색"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.validate("018f22ce-7b5a-7cc8-98c1-37a7262d2c80 검색"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
