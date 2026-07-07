package com.openat.search.domain.error;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 검색 도메인 에러코드. 클라 노출 {@code code}에는 도메인 접두사({@code SEARCH_})를 일관 적용한다(PROJECT.md §8).
 *
 * <p>실제 검색 기능 구현 시 값을 추가·수정한다. 던질 때는 {@code throw new
 * BusinessException(SearchErrorCode.INVALID_QUERY)}.
 */
@Getter
@RequiredArgsConstructor
public enum SearchErrorCode implements ErrorCode {
  INVALID_QUERY(HttpStatus.BAD_REQUEST, "SEARCH_INVALID_QUERY", "검색 조건이 올바르지 않습니다."),
  INDEX_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_INDEX_UNAVAILABLE", "검색 색인을 사용할 수 없습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
