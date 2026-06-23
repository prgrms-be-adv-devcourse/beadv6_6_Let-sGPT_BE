package com.openat.category.domain.error;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CategoryErrorCode implements ErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "존재하지 않는 카테고리입니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
