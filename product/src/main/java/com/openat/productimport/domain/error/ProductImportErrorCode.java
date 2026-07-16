package com.openat.productimport.domain.error;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductImportErrorCode implements ErrorCode {
  JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT_IMPORT_JOB_NOT_FOUND", "상품 가져오기 작업을 찾을 수 없습니다."),
  JOB_NOT_OWNER(HttpStatus.FORBIDDEN, "PRODUCT_IMPORT_JOB_NOT_OWNER", "상품 가져오기 작업에 접근할 권한이 없습니다."),
  INVALID_SOURCE(
      HttpStatus.BAD_REQUEST, "PRODUCT_IMPORT_INVALID_SOURCE", "상품 가져오기 원본 위치가 올바르지 않습니다."),
  SOURCE_NOT_ALLOWED(
      HttpStatus.FORBIDDEN, "PRODUCT_IMPORT_SOURCE_NOT_ALLOWED", "허용되지 않은 상품 가져오기 원본입니다."),
  INVALID_MANIFEST(
      HttpStatus.BAD_REQUEST, "PRODUCT_IMPORT_INVALID_MANIFEST", "products.csv 형식이 올바르지 않습니다."),
  ROW_LIMIT_EXCEEDED(
      HttpStatus.BAD_REQUEST, "PRODUCT_IMPORT_ROW_LIMIT_EXCEEDED", "허용된 상품 행 수를 초과했습니다."),
  FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "PRODUCT_IMPORT_FILE_TOO_LARGE", "허용된 파일 크기를 초과했습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
