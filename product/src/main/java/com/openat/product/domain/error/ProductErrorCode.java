package com.openat.product.domain.error;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "존재하지 않는 상품입니다."),
  NOT_OWNER(HttpStatus.FORBIDDEN, "PRODUCT_NOT_OWNER", "상품에 대한 권한이 없습니다."),
  IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_IMAGE_NOT_FOUND", "존재하지 않는 이미지입니다."),
  IMAGE_STORAGE_FAILED(
      HttpStatus.INTERNAL_SERVER_ERROR, "PRODUCT_IMAGE_STORAGE_FAILED", "이미지 저장에 실패했습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
